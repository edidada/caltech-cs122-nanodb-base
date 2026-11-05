"""Instantiate every pinned template without silently rewriting SQL features."""
from pathlib import Path
import hashlib,json,re
ROOT=Path(__file__).resolve().parents[1]

def instantiate(output):
    source=json.loads((ROOT/'schemas/tpch/query-source.json').read_text())
    parameters=json.loads((ROOT/'schemas/tpch/query-parameters.json').read_text())
    output.mkdir(parents=True,exist_ok=True)
    manifest={'scale':parameters['scale'],'parameter_policy':parameters['description'],'queries':[]}
    for n in range(1,23):
        data=(ROOT/f'schemas/tpch/query-templates/{n}.sql').read_bytes()
        entry=next(x for x in source['files'] if x['path']==f'queries/{n}.sql')
        assert hashlib.sha256(data).hexdigest()==entry['sha256']
        text=data.decode();limits=re.findall(r'^:n\s+(-?\d+)\s*$',text,re.M)
        assert len(limits)==1
        limit=int(limits[0]);p=parameters['queries'][str(n)]
        required=set(re.findall(r':(\d+)',text))
        assert required==set(p['parameters']), (n,required)
        text=re.sub(r'^:[xon]\b[^\n]*(?:\n|$)','',text,flags=re.M)
        text=re.sub(r':(\d+)',lambda m:p['parameters'][m[1]],text).replace(':s',p['stream'])
        assert not re.search(r':(?:\d+|[sxon]\b)',text)
        # dbgen :n is a result cap. Keep Q15's view statements intact; its
        # cap is -1. Other capped templates contain one SELECT statement.
        if limit>=0:
            assert text.rstrip().endswith(';')
            text=text.rstrip()[:-1]+f'\nLIMIT {limit};\n'
        target=output/f'q{n:02}.sql';target.write_text(text,encoding='utf-8')
        manifest['queries'].append({'query':n,'file':target.name,'source_sha256':entry['sha256'],'sql_sha256':hashlib.sha256(text.encode()).hexdigest(),'row_limit':limit,'parameters':p,'status':'instantiated_only_not_executed'})
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    return manifest

if __name__=='__main__':
    result=instantiate(ROOT/'target/challenge2/queries')
    print(f"Instantiated {len(result['queries'])} complete queries; SQL execution not performed")
