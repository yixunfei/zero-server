"""离线汇总独立 JFR 的采样分配与 CPU 栈；权重不是逐对象精确计数。"""
import argparse
from collections import Counter
import gzip
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime

sys.dont_write_bytecode = True
from CollectThirdRoundEvidence import json_lines, sha


def events(stream):
    """逐事件读取 jfr print JSON；长稳数据不整体载入内存。"""
    decoder = json.JSONDecoder()
    buffer = ''
    while True:
        chunk = stream.read(65536)
        if not chunk:
            raise ValueError('Missing JFR events array')
        buffer += chunk
        match = re.search(r'"events"\s*:\s*\[', buffer)
        if match:
            buffer = buffer[match.end():]
            break
    while True:
        buffer = buffer.lstrip()
        if buffer.startswith(']'):
            return
        if buffer.startswith(','):
            buffer = buffer[1:].lstrip()
        try:
            event, end = decoder.raw_decode(buffer)
        except json.JSONDecodeError:
            chunk = stream.read(65536)
            if not chunk:
                raise ValueError('Truncated or invalid JFR event JSON') from None
            buffer += chunk
            continue
        yield event
        buffer = buffer[end:]


def export(directory, jfr):
    """保留压缩原始 JSON 和摘要，避免 2h 分配采样展开成数十 GB 常驻数据。"""
    recording = directory / 'server.jfr'
    summary = subprocess.check_output([jfr, 'summary', str(recording)], text=True)
    (directory / 'jfr-summary.txt').write_text(summary, encoding='utf-8')
    output = directory / 'jfr-events.json.gz'
    command = [jfr, 'print', '--json', '--events',
               'jdk.ObjectAllocationSample,jdk.ExecutionSample,jdk.GCPhasePause,jdk.ZAllocationStall', str(recording)]
    with subprocess.Popen(command, stdout=subprocess.PIPE) as process:
        with gzip.open(output, 'wb', compresslevel=1) as stream:
            shutil.copyfileobj(process.stdout, stream, length=65536)
        if process.wait() != 0:
            raise subprocess.CalledProcessError(process.returncode, command)
    return output


def method_name(frame):
    method = frame.get('method', {})
    owner = method.get('type', {}).get('name', '?')
    return owner.replace('/', '.') + '.' + method.get('name', '?')


def analyze(directory, jfr):
    recording = directory / 'server.jfr'
    digest = sha(recording)
    analysis = directory / 'jfr-analysis.json'
    if analysis.exists():
        previous = json.loads(analysis.read_text(encoding='utf-8'))
        if previous.get('recordingSha256') == digest and previous.get('schemaVersion') == 2:
            print(json.dumps({'run': directory.name, 'status': 'unchanged analysis'}))
            return
    output = export(directory, jfr)
    with gzip.open(output, 'rt', encoding='utf-8') as stream:
        result = summarize(events(stream), directory)
    result.update({'schemaVersion': 2, 'recordingSha256': digest})
    analysis.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'run': directory.name, 'events': result['eventCounts'],
                      'topAllocation': result['allocationByClass'][:5]}, ensure_ascii=False))


def summarize(recorded_events, directory):
    allocations, allocation_stacks, cpu_stacks, counts = Counter(), Counter(), Counter(), Counter()
    first_event, last_event = None, None
    pauses, stalls = [], []
    measured = next((row for row in json_lines(directory / 'client.jsonl') if row.get('kind') == 'result'), {})
    measurement_start = measured.get('startedAtMillis', 0)
    measurement_end = measured.get('finishedAtMillis', float('inf'))
    measurement_weight = 0
    for event in recorded_events:
        kind, values = event['type'], event['values']
        counts[kind] += 1
        if 'startTime' in values:
            timestamp = values['startTime']
            first_event = min(first_event, timestamp) if first_event else timestamp
            last_event = max(last_event, timestamp) if last_event else timestamp
        frames = (values.get('stackTrace') or {}).get('frames', [])
        framework = next((method_name(frame) for frame in frames
                          if method_name(frame).startswith('group.zn.zero.')), '(outside framework)')
        if kind == 'jdk.ObjectAllocationSample':
            weight = values['weight']
            event_millis = datetime.fromisoformat(values['startTime']).timestamp() * 1000
            if measurement_start <= event_millis <= measurement_end:
                measurement_weight += weight
            allocations[values['objectClass']['name']] += weight
            allocation_stacks[framework] += weight
        elif kind == 'jdk.ExecutionSample':
            cpu_stacks[framework] += 1
        elif kind == 'jdk.GCPhasePause':
            pauses.append(values['duration'])
        elif kind == 'jdk.ZAllocationStall':
            stalls.append(values)
    event_start = datetime.fromisoformat(first_event).timestamp() * 1000 if first_event else float('inf')
    event_end = datetime.fromisoformat(last_event).timestamp() * 1000 if last_event else 0
    spans_measurement = event_start <= measurement_start and event_end >= measurement_end
    return {'samplingNote': 'Allocation weights are sampled estimates. CPU stack counts are samples, not exact CPU time. '
                            'Measurement allocation rate is omitted unless retained selected events bracket the full measurement.',
              'selectedEventsSpanMeasurement': spans_measurement,
              'firstSelectedEvent': first_event, 'lastSelectedEvent': last_event,
              'eventCounts': counts, 'allocationWeightBytes': sum(allocations.values()),
              'allocationWeightBytesDuringMeasurement': measurement_weight,
              'estimatedMeasurementAllocationMBps': measurement_weight / measured['seconds'] / 1e6
              if 'seconds' in measured and spans_measurement else None,
              'allocationByClass': allocations.most_common(20),
              'allocationByFrameworkFrame': allocation_stacks.most_common(20),
              'cpuByFrameworkFrame': cpu_stacks.most_common(20),
              'gcPhasePausesIsoDurations': pauses, 'zAllocationStalls': stalls}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', default='target/performance-third-20260923')
    parser.add_argument('--jfr', default='D:/env/jdk21/bin/jfr.exe')
    args = parser.parse_args()
    for recording in sorted(Path(args.evidence).glob('*/server.jfr')):
        if recording.stat().st_size == 0 or not (recording.parent / 'completed.json').exists():
            print(json.dumps({'run': recording.parent.name, 'status': 'recording in progress or empty'}))
            continue
        analyze(recording.parent, args.jfr)


if __name__ == '__main__':
    main()
