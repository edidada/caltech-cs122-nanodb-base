"""Actions-only actual full SQL workloads, correctness oracle and I/O comparison."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess


def expected(path):
    rows = {}
    statements = 0
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line == "EXIT;":
            continue
        statements += 1
        insert = re.fullmatch(r"INSERT INTO insert_perf VALUES \((\d+), '(.*)', ([-+\d.eE]+)\);", line)
        if insert:
            key, text, number = insert.groups()
            binary = struct.pack('>f', float(number))
            rows[int(key)] = (text, struct.unpack('>f', binary)[0],
                              struct.unpack('>i', binary)[0])
        else:
            delete = re.fullmatch(r"DELETE FROM insert_perf WHERE num >= ([-+\d.eE]+) AND num <= ([-+\d.eE]+);", line)
            if not delete:
                raise ValueError(f"Unrecognized SQL: {line}")
            low, high = map(float, delete.groups())
            rows = {key: row for key, row in rows.items() if not low <= row[1] <= high}
    encoded = ''.join(f'{key}|{row[0]}|{row[2]}\n' for key, row in sorted(rows.items()))
    return len(rows), statements, hashlib.sha256(encoded.encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path, required=True)
    args = parser.parse_args()
    current = Path.cwd()
    output = current / 'target/hw1-performance'
    output.mkdir(parents=True, exist_ok=True)
    config = output / 'log4j2.xml'
    config.write_text('<Configuration><Loggers><Root level="off"/></Loggers></Configuration>')
    results = {}
    for label, root in [('first_fit', args.baseline.resolve()), ('indexed', current)]:
        subprocess.run(['mvn', '-B', '-DskipTests', 'package', 'dependency:build-classpath',
                        '-Dmdep.outputFile=target/benchmark-classpath.txt'], cwd=root, check=True)
        cp = os.pathsep.join([str(root / 'target/classes'),
                              (root / 'target/benchmark-classpath.txt').read_text().strip()])
        classes = output / label
        classes.mkdir(exist_ok=True)
        subprocess.run(['javac', '-cp', cp, '-d', str(classes),
                        str(current / 'scripts/Hw1Benchmark.java')], check=True)
        cp = os.pathsep.join([str(classes), cp])
        results[label] = {}
        for workload in ['ins20k.sql', 'ins50k.sql', 'ins50k-del.sql']:
            path = current / 'schemas/insert-perf' / workload
            run = subprocess.run(['java', '-Xmx1g', f'-Dlog4j.configurationFile={config}',
                                  '-cp', cp, 'Hw1Benchmark', str(path)],
                                 capture_output=True, text=True, timeout=1800)
            (output / f'{label}-{workload}.stdout').write_text(run.stdout)
            (output / f'{label}-{workload}.stderr').write_text(run.stderr)
            if run.returncode:
                raise RuntimeError(f'{label}/{workload}: exit {run.returncode}\n'
                                   f'{run.stderr[-8000:]}\n{run.stdout[-8000:]}')
            data = json.loads(run.stdout.strip())
            count, statements, digest = expected(path)
            assert (data['rows'], data['statements'], data['rowsSha256']) == (count, statements, digest), data
            results[label][workload] = data
            print(label, data, flush=True)
            (output / 'results.json').write_text(json.dumps(results, indent=2))
    for workload, baseline in results['first_fit'].items():
        indexed = results['indexed'][workload]
        assert indexed['bytes'] <= baseline['bytes'] * 1.05, (workload, baseline, indexed)
        assert indexed['pagesRead'] < baseline['pagesRead'], (workload, baseline, indexed)
        # The course explicitly asks students to discuss potential added seeks.
        # Reading fewer pages does not imply that this sector-distance proxy
        # decreases. Keep a declared self-study 5% regression budget instead;
        # publish both counters, including the extra metadata writes.
        assert indexed['fileDistanceTraveled'] <= baseline['fileDistanceTraveled'] * 1.05, (workload, baseline, indexed)


if __name__ == '__main__':
    main()
