"""Official CS122 Challenge1 SF0.01, full-feature execution on Actions only."""
from pathlib import Path
import csv, decimal, hashlib, json, os, re, ssl, subprocess, tarfile, urllib.error, urllib.request

ROOT = Path(__file__).resolve().parents[1]
TABLES = ['region','nation','part','supplier','partsupp','customer','orders','lineitem']

def digest(data):
    return hashlib.sha256(data).hexdigest()

def prepare(directory):
    directory.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((ROOT/'scripts/challenge1-input-manifest.json').read_text(encoding='utf-8'))
    for source in manifest['sources']:
        path=directory/source['url'].rsplit('/',1)[1]
        if not path.exists():
            try:
                with urllib.request.urlopen(source['url'],timeout=90) as response:
                    payload=response.read()
            except urllib.error.URLError as error:
                # The official course host omits an issuer certificate. Only
                # these exact pinned public assets permit this transport fallback.
                if not isinstance(error.reason,ssl.SSLCertVerificationError):
                    raise
                assert source['url'].startswith('https://courses.cms.caltech.edu/cs122/assignments/challenge1/')
                print('Official host certificate-chain failure; pinned SHA256 required:',source['url'])
                with urllib.request.urlopen(source['url'],context=ssl._create_unverified_context(),timeout=90) as response:
                    payload=response.read()
            assert len(payload)==source['bytes'] and digest(payload)==source['sha256'],source['url']
            path.write_bytes(payload)
        assert path.stat().st_size==source['bytes'] and digest(path.read_bytes())==source['sha256'],path
    schema=(directory/'make-tpch.sql').read_text()
    oracle={'tables':{},'customer_groups':{}}
    with tarfile.open(directory/'s-0.01.tbz') as archive:
        for member in manifest['sql_members']:
            name=Path(member['name']).stem
            assert name in TABLES
            data=archive.extractfile(member['name']).read()
            assert len(data)==member['bytes'] and digest(data)==member['sha256']
            (directory/(name+'.sql')).write_bytes(data)
            body=re.search(r'CREATE TABLE '+name+r'\s*\((.*?)\n\);',schema,re.I|re.S).group(1)
            types=re.findall(r'^\s+[A-Z_]+\s+(INTEGER|DECIMAL|CHAR|VARCHAR|DATE)',body,re.M)
            rows=[]
            for line in data.decode().splitlines():
                match=re.fullmatch(r'INSERT INTO '+name+r' VALUES \((.*)\);',line,re.I)
                assert match,line
                values=next(csv.reader([match.group(1)],quotechar="'",doublequote=True,skipinitialspace=True))
                assert len(values)==len(types),(name,values,types)
                for i,kind in enumerate(types):
                    if kind in ('INTEGER','DECIMAL'):
                        number=decimal.Decimal(values[i]);values[i]='0' if number==0 else format(number.normalize(),'f')
                rows.append(json.dumps(values,ensure_ascii=False,separators=(',',':')))
                if name=='customer':
                    key=values[3];oracle['customer_groups'][key]=oracle['customer_groups'].get(key,0)+1
            assert len(rows)==member['insert_statements']
            oracle['tables'][name]={'rows':len(rows),'rowSha256':digest((''.join(row+'\n' for row in sorted(rows))).encode())}
    (directory/'oracle.json').write_text(json.dumps(oracle,indent=2)+'\n')
    return oracle

def main():
    output=ROOT/'target/challenge1';inputs=output/'inputs';oracle=prepare(inputs)
    output.mkdir(parents=True,exist_ok=True)
    config=output/'log4j2.xml';config.write_text('<Configuration><Loggers><Root level="off"/></Loggers></Configuration>')
    subprocess.run(['mvn','-B','-DskipTests','package','dependency:build-classpath','-Dmdep.outputFile=target/challenge1-classpath.txt'],check=True)
    cp=os.pathsep.join([str(ROOT/'target/classes'),(ROOT/'target/challenge1-classpath.txt').read_text().strip()])
    subprocess.run(['javac','-cp',cp,'-d',str(output),str(ROOT/'scripts/Challenge1.java')],check=True)
    with (output/'execution.log').open('w') as log:
        subprocess.run(['java','-Xmx2g',f'-Dlog4j.configurationFile={config}','-cp',os.pathsep.join([str(output),cp]),'Challenge1',str(inputs),str(output)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=1800)
    result=json.loads((output/'results.json').read_text())
    assert result['features']=={'transactions':True,'indexes':True,'keyConstraints':True,'flushAfterCmd':False}
    assert result['bufferPoolBytes']==10485760
    assert 0 < result['peakPoolBytes'] <= 10485760
    assert result['internalWritebackReserveBytes']==262144
    assert result['loadTransactions']==len(TABLES)
    assert {table['name'] for table in result['tables']}==set(TABLES)
    for table in result['tables']:
        expected=oracle['tables'][table['name']]
        assert table['rows']==table['sqlCount']==table['statsRows']==expected['rows'],table
        assert table['rowSha256']==expected['rowSha256'],table
        assert table['indexes'] and all(index['rows']==expected['rows'] and not index['errors'] for index in table['indexes']),table
    assert result['customerGroups']==oracle['customer_groups']
    result['verification']={'oracle':'independent CSV/Decimal parsing of pinned official INSERT files','commit':os.environ.get('GITHUB_SHA'),'conclusion':'success'}
    (output/'results.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    main()
