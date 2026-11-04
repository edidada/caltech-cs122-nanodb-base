"""Run the official dataset and complete required costing experiments on Actions."""
import json
from pathlib import Path
import os
import subprocess
import sys

root=Path.cwd()
out=root/'target/hw4-experiments'
out.mkdir(parents=True,exist_ok=True)
config=out/'log4j2.xml'
config.write_text('<Configuration><Loggers><Root level="off"/></Loggers></Configuration>')
subprocess.run(['mvn','-B','-DskipTests','package','dependency:build-classpath','-Dmdep.outputFile=target/hw4-classpath.txt'],check=True)
cp=os.pathsep.join([str(root/'target/classes'),(root/'target/hw4-classpath.txt').read_text().strip()])
subprocess.run(['javac','-cp',cp,'-d',str(out),str(root/'scripts/Hw3Experiments.java')],check=True)
subprocess.run(['java','-Xmx1g',f'-Dlog4j.configurationFile={config}','-cp',os.pathsep.join([str(out),cp]),'Hw3Experiments',str(out)],check=True,timeout=1800)
data=json.loads((out/'results.json').read_text())
tables={table['name']:table for table in data['tables']}
for name,rows in {'states':51,'cities':254,'stores':2000,'employees':24932}.items():
    assert tables[name]['rows']==rows,(name,tables[name])
assert tables['employees']['columns'][-1]['nulls']==4259
assert tables['employees']['columns'][-1]['distinct']==11754
for table in tables.values():
    for column in table['columns']:
        if column['name'].endswith('name'):
            assert column['min'] is None and column['max'] is None
queries={query['name']:query for query in data['queries']}
for names in [('join_original','join_rewritten'),('oregon_original','oregon_rewritten'),('range_direct','range_low_first','range_high_first')]:
    assert len({queries[name]['resultSha256'] for name in names})==1,names
assert queries['oregon_original']['actualRows']==7
assert queries['range_direct']['actualRows']==179
for names in [('oregon_original','oregon_order_b','oregon_order_c')]:
    assert len({queries[name]['resultSha256'] for name in names})==1
    # Public-order restoration projections can differ by original FROM order.
    # Compare only the canonical join tree, excluding the final summary and
    # normalizing indentation; retain each complete EXPLAIN in results.json.
    def core(explanation):
        tree=explanation.split('\n\nEstimated ',1)[0]
        lines=tree.splitlines()
        start=next(i for i,line in enumerate(lines) if 'NestedLoop[' in line)
        indent=len(lines[start])-len(lines[start].lstrip())
        return '\n'.join(line[indent:] for line in lines[start:])
    cores=[core(queries[name]['explain']) for name in names]
    assert len(set(cores))==1, cores
baseline=json.loads((root/'reports/hw3-results.json').read_text())
old={q['name']:q for q in baseline['queries']}
for name in old:
    assert queries[name]['resultSha256']==old[name]['resultSha256'],name
if '--updated-expression-costs' not in sys.argv:
    assert queries['oregon_original']['cpuCost']<old['oregon_original']['cpuCost']
# HW5 uses a different cost unit: retain historical result hashes, but compare
# optimizer costs only against a syntax-order plan using that same model in tests.
print(json.dumps(data,indent=2))
