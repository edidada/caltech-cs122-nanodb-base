"""Run the official dataset and complete required costing experiments on Actions."""
import json
from pathlib import Path
import os
import subprocess

root=Path.cwd()
out=root/'target/hw3-experiments'
out.mkdir(parents=True,exist_ok=True)
config=out/'log4j2.xml'
config.write_text('<Configuration><Loggers><Root level="off"/></Loggers></Configuration>')
subprocess.run(['mvn','-B','-DskipTests','package','dependency:build-classpath','-Dmdep.outputFile=target/hw3-classpath.txt'],check=True)
cp=os.pathsep.join([str(root/'target/classes'),(root/'target/hw3-classpath.txt').read_text().strip()])
subprocess.run(['javac','-cp',cp,'-d',str(out),str(root/'scripts/Hw3Experiments.java')],check=True)
subprocess.run(['java','-Xmx1g',f'-Dlog4j.configurationFile={config}','-cp',os.pathsep.join([str(out),cp]),'Hw3Experiments'],check=True,timeout=1800)
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
assert queries['oregon_rewritten']['cpuCost']<queries['oregon_original']['cpuCost']
assert queries['join_rewritten']['cpuCost']<queries['join_original']['cpuCost']
print(json.dumps(data,indent=2))
