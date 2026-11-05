"""Run every query in isolation. Success here means observed execution only."""
from pathlib import Path
import decimal,hashlib,json,os,shutil,subprocess,sys,time
from instantiate_tpch_queries import instantiate,ROOT

def main():
    output=ROOT/'target/challenge2';output.mkdir(parents=True,exist_ok=True)
    manifest=instantiate(output/'queries')
    baseline=ROOT/'target/challenge1/datafiles'
    accepted=json.loads((ROOT/'target/challenge1/results.json').read_text())
    assert accepted['verification']['conclusion']=='success'
    assert sum(x['rows'] for x in accepted['tables'])==86805
    subprocess.run(['mvn','-B','-DskipTests','package','dependency:build-classpath','-Dmdep.outputFile=target/challenge2-classpath.txt'],check=True)
    cp=os.pathsep.join([str(ROOT/'target/classes'),(ROOT/'target/challenge2-classpath.txt').read_text().strip()])
    subprocess.run(['javac','-cp',cp,'-d',str(output),str(ROOT/'scripts/Challenge2Query.java')],check=True)
    config=output/'log4j2.xml';config.write_text('<Configuration><Loggers><Root level="off"/></Loggers></Configuration>')
    oracle_db=output/'oracle.duckdb'
    subprocess.run([sys.executable,str(ROOT/'scripts/challenge2_oracle.py'),'build',str(ROOT/'target/challenge1/inputs'),str(oracle_db)],check=True,timeout=600)
    matrix={'commit':os.environ.get('GITHUB_SHA'),'baseline':accepted['verification'],'scope':'all 22 instantiated templates; unmodified SQL features','oracle_status':'per_query_independent_duckdb_comparison','queries':[]}
    for query in manifest['queries']:
        result_dir=output/f"q{query['query']:02}";result_dir.mkdir(exist_ok=True)
        # A single disposable copy at a time bounds disk use, including Q15 DDL.
        scratch=output/'scratch-datafiles'
        if scratch.exists():shutil.rmtree(scratch)
        shutil.copytree(baseline,scratch)
        record=dict(query);record['oracle_status']='not_compared'
        command=['java','-Xmx2g',f'-Dlog4j.configurationFile={config}','-cp',os.pathsep.join([str(output),cp]),'Challenge2Query',str(scratch),str(output/'queries'/query['file']),str(result_dir)]
        started=time.monotonic()
        try:
            with (result_dir/'process.log').open('w') as log:
                run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=120)
            record['exit_code']=run.returncode
            record['status']='executed' if run.returncode==0 else 'failure'
        except subprocess.TimeoutExpired:
            record['status']='timeout';record['timeout_seconds']=120
        record['seconds']=time.monotonic()-started
        stage=result_dir/'stage.txt';record['phase']=stage.read_text() if stage.exists() else 'process_start'
        failure=result_dir/'failure.txt'
        if failure.exists():record['failure']=failure.read_text()
        rows=result_dir/'rows.jsonl'
        if record['status']=='executed':
            assert record['phase']=='success' and rows.exists()
            values=[json.loads(line) for line in rows.read_text().splitlines()]
            record['rows']=len(values);record['ordered_output_sha256']=hashlib.sha256(rows.read_bytes()).hexdigest()
        oracle_output=result_dir/'oracle.json'
        oracle_started=time.monotonic()
        try:
            oracle_run=subprocess.run([sys.executable,str(ROOT/'scripts/challenge2_oracle.py'),'query',str(oracle_db),str(output/'queries'/query['file']),str(oracle_output)],timeout=120)
            oracle=json.loads(oracle_output.read_text()) if oracle_run.returncode==0 and oracle_output.exists() else {'status':'process_failure'}
        except subprocess.TimeoutExpired:oracle={'status':'timeout'}
        record['oracle_seconds']=time.monotonic()-oracle_started
        record['oracle_execution']=oracle['status']
        if record['status']=='executed' and oracle['status']=='executed':
            expected=oracle['rows']
            numeric_columns=oracle['numeric_columns']
            def equal_value(actual,wanted,numeric):
                if actual is None or wanted is None:return actual is wanted
                if not numeric:return actual==wanted
                try:
                    left=decimal.Decimal(actual);right=decimal.Decimal(wanted)
                    return abs(left-right)<=max(decimal.Decimal('0.00001'),abs(right)*decimal.Decimal('0.0000001'))
                except decimal.InvalidOperation:return False
            # Ordered comparison is deliberately conservative: ties may cause
            # a false mismatch, but never silently discard duplicate rows.
            match=len(values)==len(expected) and all(len(a)==len(b) and len(a)==len(numeric_columns) and all(equal_value(x,y,numeric) for x,y,numeric in zip(a,b,numeric_columns)) for a,b in zip(values,expected))
            record['oracle_status']='match' if match else 'mismatch'
            record['oracle_rows']=len(expected)
        else:record['oracle_status']='not_comparable'
        matrix['queries'].append(record)
        (output/'matrix.json').write_text(json.dumps(matrix,indent=2)+'\n')
        shutil.rmtree(scratch)
        print(query['query'],record['status'],record['phase'],flush=True)
    assert [q['query'] for q in matrix['queries']]==list(range(1,23))
    matrix['matched_queries']=[q['query'] for q in matrix['queries'] if q['oracle_status']=='match']
    matrix['unaccepted_queries']=[q['query'] for q in matrix['queries'] if q['oracle_status']!='match']
    matrix['numeric_tolerance']={'absolute':'0.00001','relative':'0.0000001','ordering':'strict ordered rows; ties may be conservatively rejected'}
    (output/'matrix.json').write_text(json.dumps(matrix,indent=2)+'\n')
    print('All 22 attempts recorded; matched:',matrix['matched_queries'])
    print('Not accepted:',matrix['unaccepted_queries'])

if __name__=='__main__':main()
