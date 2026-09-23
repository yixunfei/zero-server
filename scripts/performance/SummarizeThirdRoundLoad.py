"""汇总已完成负载；计时区间与启动/预热 GC 明确分开，不把峰值内存当泄漏。"""
import argparse
import json
import math
from pathlib import Path
import statistics
import sys

sys.dont_write_bytecode = True
from CollectThirdRoundEvidence import gc_summary, json_lines


def describe(values):
    return {'min': min(values, default=0), 'max': max(values, default=0),
            'median': statistics.median(values) if values else 0,
            'first': values[0] if values else 0, 'last': values[-1] if values else 0}


def windows(rows, names, start, end):
    """按 15 分钟观察长稳资源；中位数和范围不等于 GC 后存活量。"""
    if not math.isfinite(end - start) or end - start < 30 * 60 * 1000:
        return []
    result = []
    for offset in range(0, int(end - start), 15 * 60 * 1000):
        selected = [row for row in rows if start + offset <= row['timeMillis']
                    < min(start + offset + 15 * 60 * 1000, end)]
        if not selected:
            continue
        result.append({'fromSeconds': offset / 1000,
                       'toSeconds': min(offset + 15 * 60 * 1000, end - start) / 1000,
                       'samples': len(selected),
                       'metrics': {name: describe([row[name] for row in selected]) for name in names}})
    return result


def summarize(directory):
    client = json_lines(directory / 'client.jsonl')
    results = [row for row in client if row.get('kind') == 'result']
    if not results:
        return None
    result = results[-1]
    start, end = result.get('startedAtMillis', 0), result.get('finishedAtMillis', float('inf'))
    os_samples = [row for row in json_lines(directory / 'os-resources.jsonl')
                  if start <= row['timeMillis'] <= end]
    resources = {}
    for role in ('server', 'client'):
        selected = [row for row in os_samples if row['role'] == role]
        metrics = {name: describe([row[name] for row in selected])
                   for name in ('rssBytes', 'privateBytes', 'handles')}
        if len(selected) > 1:
            metrics['averageCpuCores'] = ((selected[-1]['cpuSeconds'] - selected[0]['cpuSeconds'])
                    / ((selected[-1]['timeMillis'] - selected[0]['timeMillis']) / 1000))
        metrics['fifteenMinuteWindows'] = windows(selected, ('rssBytes', 'privateBytes', 'handles'), start, end)
        resources[role] = metrics
    server_rows = json_lines(directory / 'server.jsonl')
    during = [row for row in server_rows if start <= row['timeMillis'] <= end]
    final = next((row for row in reversed(server_rows) if row['role'] == 'server-final'), None)
    return {'result': result, 'resourceInterval': 'measurement' if 'startedAtMillis' in result else 'whole run; burst has no wall-clock bounds',
            'osDuringMeasurement': resources,
            'serverDuringMeasurement': {name: describe([row[name] for row in during])
                                        for name in ('heapUsed', 'directBytes', 'threads')},
            'serverFifteenMinuteWindows': windows(during, ('heapUsed', 'directBytes', 'threads'), start, end),
            'serverAfterShutdown': final, 'gcIncludingStartupWarmupShutdown': gc_summary(directory)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', default='target/performance-third-20260923')
    args = parser.parse_args()
    root = Path(args.evidence)
    summaries = {}
    for directory in sorted(root.iterdir()):
        if directory.is_dir() and (directory / 'completed.json').exists():
            value = summarize(directory)
            if value is not None:
                summaries[directory.name] = value
    (root / 'load-summary.json').write_text(json.dumps(summaries, indent=2) + '\n', encoding='utf-8')
    print('| Run | Completed/offered | failed/timeout/rejected | p99 ms | p999 ms | Server RSS peak MB | GC max ms |')
    print('| --- | ---: | ---: | ---: | ---: | ---: | ---: |')
    for name, row in summaries.items():
        result = row['result']
        peak = row['osDuringMeasurement']['server']['rssBytes']['max'] / 1e6
        pause = row['gcIncludingStartupWarmupShutdown']['pauseEvents']['maxMillis']
        p99 = result.get('p99ns', result.get('responseP99ns', 0)) / 1e6
        p999 = f"{result['p999ns'] / 1e6:.3f}" if 'p999ns' in result else 'N/A'
        errors = '/'.join(str(result.get(key, 0)) for key in ('failed', 'timeouts', 'rejected'))
        print(f"| {name} | {result['completed']}/{result['offered']} | {errors} | {p99:.3f} | {p999} | {peak:.1f} | {pause:.3f} |")


if __name__ == '__main__':
    main()
