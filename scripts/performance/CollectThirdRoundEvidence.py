"""收集第三轮实际证据；不推断未执行的验证，也不重复累加测试报告。"""
import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def json_lines(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines()
            if line.startswith('{')]


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def source_manifest():
    files = set(git('ls-files', '-c', '-o', '--exclude-standard').splitlines())
    return {name: sha(Path(name)) for name in sorted(files)
            if Path(name).is_file() and not name.startswith(('.workbuddy/', '.workbuddy-ai/'))
            and Path(name).suffix in {'.java', '.xml', '.ps1', '.py', '.si'}
            and '/target/' not in name}


def tests():
    cases = {}
    # 仅本次 reactor 的模块报告；排除 target 下历史 scaffold 和独立 examples。
    reports = list(Path('.').glob('zero-*/target/surefire-reports/TEST-*.xml'))
    for path in reports:
        module = path.as_posix().split('/target/')[0]
        for case in ET.parse(path).getroot().iter('testcase'):
            key = (module, case.attrib.get('classname'), case.attrib.get('name'))
            cases[key] = next((tag for tag in ('failure', 'error', 'skipped')
                               if case.find(tag) is not None), 'passed')
    return {'xmlReports': len(reports), 'uniqueTests': len(cases),
            'counts': {status: list(cases.values()).count(status)
                       for status in ('passed', 'failure', 'error', 'skipped')}}


def benchmarks(root):
    result = {}
    for path in sorted(root.glob('*.json')):
        if not path.stat().st_size:
            continue
        rows = read_json(path)
        if not isinstance(rows, list) or not rows or 'benchmark' not in rows[0]:
            continue
        command = path.with_suffix('.command.txt')
        kept = []
        for row in rows:
            item = {k: row[k] for k in ('benchmark', 'mode', 'threads', 'forks', 'jdkVersion',
                                        'jvmArgs', 'warmupIterations', 'warmupTime',
                                        'measurementIterations', 'measurementTime')}
            item['params'] = row.get('params', {})
            metrics = {'primary': row['primaryMetric'], **row['secondaryMetrics']}
            item['metrics'] = {name: {k: metric[k] for k in
                              ('score', 'scoreError', 'scoreConfidence', 'scoreUnit', 'rawData')
                              if k in metric} for name, metric in metrics.items()}
            kept.append(item)
        library = next((name for name in ('stale-actor', 'baseline', 'candidate', 'current', 'longadder', 'final')
                        if path.stem.endswith('-' + name) or '-' + name + '-' in path.stem), None)
        harness = 'extra-harness-final.jar' if path.name.startswith('event-composition-') else 'extra-harness.jar'
        extra = path.name.startswith(('event-composition-', 'actor-observation-', 'outbound-shared-'))
        result[path.name] = {'sha256': sha(path), 'measurements': kept,
                             'library': library, 'librarySha256': sha(root / (library + '.jar')) if library else None,
                             'extraHarnessSha256': sha(root / harness) if extra else None,
                             'command': command.read_text().strip() if command.exists() else None}
    return result


def gc_summary(root):
    pauses, stalls = [], []
    for path in root.glob('gc.log*'):
        for line in path.read_text(errors='replace').splitlines():
            duration = re.search(r'([\d.]+)ms\s*$', line)
            if duration and 'Pause' in line and 'gc,start' not in line:
                pauses.append(float(duration[1]))
            if duration and 'Allocation Stall' in line:
                stalls.append(float(duration[1]))
    def stats(values):
        ordered = sorted(values)
        return {'count': len(values), 'totalMillis': sum(values),
                'maxMillis': max(values, default=0),
                'p99Millis': ordered[min(len(ordered)-1, int(len(ordered)*.99))] if ordered else 0}
    return {'pauseEvents': stats(pauses), 'allocationStalls': stats(stalls)}


def loads(root):
    result = {}
    for directory in sorted(root.iterdir()):
        metadata = directory / 'metadata.json'
        if not directory.is_dir() or not metadata.exists():
            continue
        client = json_lines(directory / 'client.jsonl')
        server = json_lines(directory / 'server.jsonl')
        measurements = [row for row in client if row.get('kind') == 'result']
        completed = (directory / 'completed.json').exists()
        if completed and len(measurements) != 1:
            raise ValueError('Completed run must contain exactly one measurement: ' + directory.name)
        for row in measurements:
            accounted = sum(row.get(key, 0) for key in ('completed', 'failed', 'timeouts', 'rejected', 'pending'))
            if accounted != row['offered']:
                raise ValueError('Load result accounting mismatch: ' + directory.name)
        result[directory.name] = {
            'status': 'completed' if completed else 'incomplete-or-failed',
            'completed': read_json(directory / 'completed.json') if completed else None,
            'metadata': read_json(metadata), 'client': client, 'server': server,
            'osResources': json_lines(directory / 'os-resources.jsonl'),
            'jfrRepositoryObservations': json_lines(directory / 'jfr-repository-observations.jsonl'),
            'gc': gc_summary(directory),
            'jfr': read_json(directory / 'jfr-analysis.json')
            if (directory / 'jfr-analysis.json').exists() else
            {'status': 'empty-or-not-yet-flushed'} if (directory / 'server.jfr').exists()
            and (directory / 'server.jfr').stat().st_size == 0 else None,
            'files': {p.name: {'sha256': sha(p), 'bytes': p.stat().st_size}
                      for p in sorted(directory.iterdir()) if p.is_file()},
        }
    return result


def bytecode(root):
    if not (root / 'final.jar').exists():
        return {'status': 'final jar not yet built'}
    prefixes = ('group/zn/zero/actor/scheduler/ExecutorActorScheduler',
                'group/zn/zero/net/netty/NettyZeroBuffer',
                'group/zn/zero/net/netty/NettyProtocolFrameEncoder')
    delivery_path = root / 'delivery.jar' if (root / 'delivery.jar').exists() else root / 'final.jar'
    with zipfile.ZipFile(root / 'baseline.jar') as baseline, zipfile.ZipFile(root / 'final.jar') as final, \
            zipfile.ZipFile(delivery_path) as delivery:
        def selected(name, roots):
            return name.endswith('.class') and any(name == prefix + '.class'
                       or name.startswith(prefix + '$') for prefix in roots)
        restored = {name: baseline.read(name) == final.read(name) for name in baseline.namelist()
                    if selected(name, prefixes)}
        if set(restored) != {name for name in final.namelist() if selected(name, prefixes)}:
            raise RuntimeError('Restored classes still contain a rejected experiment class')
        names = set(delivery.namelist())
        matched, production_matched, mismatches = 0, 0, []
        for path in Path('.').glob('zero-*/target/classes/**/*.class'):
            relative = path.as_posix().split('/target/classes/')[1]
            if relative not in names:
                continue
            if path.read_bytes() == delivery.read(relative):
                matched += 1
            else:
                mismatches.append(str(path))
            if path.parts[0] != 'zero-benchmarks':
                if path.read_bytes() != final.read(relative):
                    mismatches.append('Measured production class differs: ' + str(path))
                else:
                    production_matched += 1
        accepted = ('group/zn/zero/actor/ActorMessageIds', 'group/zn/zero/aoi/InMemoryAoiIndex',
                    'group/zn/zero/aoi/ObserverState', 'group/zn/zero/event/bus/InMemoryEventBus',
                    'group/zn/zero/net/netty/NettyOutbound', 'group/zn/zero/net/netty/NettyConnection')
        with zipfile.ZipFile(root / 'current.jar') as confirmed:
            confirm_matches = {name: confirmed.read(name) == final.read(name)
                               for name in confirmed.namelist()
                               if selected(name, accepted)}
    if not all(restored.values()) or not all(confirm_matches.values()) or mismatches:
        raise RuntimeError('Final artifact does not match restored/confirmed/compiled classes: '
                           + json.dumps([restored, confirm_matches, mismatches]))
    return {'restoredClassesEqualBaseline': restored, 'confirmedClassesMatchFinal': confirm_matches,
            'deliveryArtifact': delivery_path.name, 'deliveryMatchesCompiledClasses': matched,
            'measuredProductionMatchesCompiledClasses': production_matched, 'mismatches': mismatches}


def load_drivers(root):
    """测量覆盖类与重编译类比较反汇编，忽略调试属性引起的常量池编号变化。"""
    driver_root = root / 'load-classes'
    compiled = Path('zero-benchmarks/target/classes')
    paths = sorted(path for path in driver_root.rglob('*.class')
                   if (compiled / path.relative_to(driver_root)).exists())
    if not paths:
        raise RuntimeError('No compiled load drivers found')
    names = [path.relative_to(driver_root).as_posix()[:-6].replace('/', '.') for path in paths]
    command = ['D:/env/jdk21/bin/javap.exe', '-p', '-c', '-s', '-constants']
    def disassembly(classpath):
        output = subprocess.check_output(command + ['-classpath', str(classpath), *names], text=True)
        return re.sub(r'#\d+', '#', output)
    matches = disassembly(driver_root) == disassembly(compiled)
    result = {'classes': names, 'comparison': 'javap -p -c -s -constants; constant pool indices normalized',
              'measuredDriversMatchCompiledCode': matches}
    if not matches:
        raise RuntimeError('Measured driver code differs from delivery build')
    result['metadataCheckedRuns'] = []
    for metadata in sorted(root.glob('*/metadata.json')):
        value = read_json(metadata)
        if not (metadata.parent / 'completed.json').exists():
            continue
        measured_root = Path(value.get('driverClasses') or driver_root)
        known = {path.name: sha(path).upper() for path in measured_root.rglob('*.class')}
        for driver in value.get('drivers', []):
            if known.get(driver['name']) != driver['sha256'].upper():
                raise RuntimeError('Measured driver digest changed: ' + str(metadata) + ': ' + driver['name'])
        for role in ('server', 'client'):
            if sha(Path(value[role + 'Jar'])).upper() != value[role + 'Sha256'].upper():
                raise RuntimeError('Measured library digest changed: ' + str(metadata))
        result['metadataCheckedRuns'].append(metadata.parent.name)
    (root / 'delivery-driver-bytecode.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def dependencies(root):
    """从实测 fat JAR 读取 Maven 坐标，避免把当前本地仓库解析结果误当实测依赖。"""
    with zipfile.ZipFile(root / 'final.jar') as library:
        result = {}
        for name in library.namelist():
            if name.startswith('META-INF/maven/') and name.endswith('/pom.properties'):
                properties = dict(line.split('=', 1) for line in library.read(name).decode('utf-8').splitlines()
                                  if '=' in line and not line.startswith(('#', '!')))
                result[properties['groupId'] + ':' + properties['artifactId']] = properties['version']
        return dict(sorted(result.items()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', default='target/performance-third-20260923')
    parser.add_argument('--output', default='docs/reports/performance-third-20260923.samples.json')
    args = parser.parse_args()
    root = Path(args.evidence)
    drivers = load_drivers(root) if (root / 'delivery.jar').exists() else {'status': 'awaiting delivery build'}
    result = {'capturedUtc': datetime.now(timezone.utc).isoformat(),
              'baselineHead': 'd2a701aec49b46285c2e1e5c2b8e150bf5355dfd',
              'head': git('rev-parse', 'HEAD'), 'branch': git('branch', '--show-current'),
              'environment': read_json(root / 'environment.json'),
              'artifacts': {p.name: sha(p) for p in sorted(root.glob('*.jar'))},
              'measuredDependencies': dependencies(root),
              'sourceManifest': source_manifest(), 'tests': tests(), 'bytecode': bytecode(root), 'loadDrivers': drivers,
              'verificationLogs': {name: sha(root / name) for name in
                  ('final-quality.log', 'net-clean-quality.log', 'actor-clean-quality.log',
                   'delivery-quality.log', 'load-driver-quality.log', 'architecture-final.log',
                   'api-final.log', 'delivery-driver-bytecode.json') if (root / name).exists()},
              'finalizationCommands': (root / 'FinishThirdRound.ps1').read_text(encoding='utf-8-sig')
              if (root / 'FinishThirdRound.ps1').exists() else None,
              'loadSummary': read_json(root / 'load-summary.json') if (root / 'load-summary.json').exists() else None,
              'jmh': benchmarks(root), 'loads': loads(root),
              'discardedMeasurements': {'pattern': '*stale-actor*',
                  'reason': 'Maven incremental output retained the rejected striped actor experiment after source restore. '
                            'These samples and jar are retained for audit and excluded from final conclusions.'},
              'retention': {p.name: read_json(p) for p in root.glob('aoi-retention-*.json')},
              'baselineSourceManifestSha256': sha(root / 'baseline-sources.json')}
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)
                                + '\n', encoding='utf-8')
    print(json.dumps({'output': args.output, 'tests': result['tests'], 'jmhFiles': len(result['jmh']),
                      'loadRuns': len(result['loads']), 'bytecode': result['bytecode']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
