"""Independent DuckDB oracle; only invoked on Actions, never local lab runs."""
from pathlib import Path
import csv,datetime,decimal,json,re,sys

def canonical(value):
    if value is None:return None
    if isinstance(value,(int,float,decimal.Decimal)):
        d=decimal.Decimal(str(value))
        return '0' if not d else format(d.normalize(),'f')
    return str(value)

def statements(text):
    # Pinned templates contain no semicolons in quoted strings. Assert this
    # rather than silently splitting an arbitrary SQL input incorrectly.
    text=re.sub(r'--[^\n]*','',text)
    quoted=re.findall(r"'(?:[^']|'')*'",text)
    assert all(';' not in value for value in quoted)
    return [part.strip() for part in text.split(';') if part.strip()]

def build(inputs,path):
    import duckdb
    schema=(inputs/'make-tpch.sql').read_text()
    con=duckdb.connect(str(path))
    con.execute(schema)
    counts={}
    for name in ['region','nation','part','supplier','partsupp','customer','orders','lineitem']:
        body=re.search(r'CREATE TABLE '+name+r'\s*\((.*?)\n\);',schema,re.I|re.S).group(1)
        types=re.findall(r'^\s+[A-Z_]+\s+(INTEGER|DECIMAL|CHAR|VARCHAR|DATE)',body,re.M)
        rows=[]
        for line in (inputs/(name+'.sql')).read_text().splitlines():
            match=re.fullmatch(r'INSERT INTO '+name+r' VALUES \((.*)\);',line,re.I)
            assert match,line
            values=next(csv.reader([match.group(1)],quotechar="'",doublequote=True,skipinitialspace=True))
            assert len(values)==len(types)
            row=[]
            for kind,value in zip(types,values):
                row.append(int(value) if kind=='INTEGER' else decimal.Decimal(value) if kind=='DECIMAL' else datetime.date.fromisoformat(value) if kind=='DATE' else value)
            rows.append(row)
        con.executemany('INSERT INTO '+name+' VALUES ('+','.join('?' for _ in types)+')',rows)
        count=con.execute('SELECT count(*) FROM '+name).fetchone()[0]
        assert count==len(rows);counts[name]=count
    assert sum(counts.values())==86805
    con.close()
    path.with_suffix('.json').write_text(json.dumps({'engine':'duckdb','version':duckdb.__version__,'counts':counts},indent=2)+'\n')

def query(database,sql,output):
    import duckdb
    result={'engine':'duckdb','version':duckdb.__version__,'status':'failure'}
    con=duckdb.connect(str(database));rows=None
    try:
        for statement in statements(sql.read_text()):
            cursor=con.execute(statement)
            if statement.lstrip().lower().startswith('select'):
                rows=[[canonical(v) for v in row] for row in cursor.fetchall()]
        assert rows is not None
        result.update(status='executed',rows=rows)
    except Exception as error:result['error']=repr(error)
    finally:con.close()
    output.write_text(json.dumps(result,indent=2)+'\n')

if __name__=='__main__':
    if sys.argv[1]=='build':build(Path(sys.argv[2]),Path(sys.argv[3]))
    elif sys.argv[1]=='query':query(Path(sys.argv[2]),Path(sys.argv[3]),Path(sys.argv[4]))
    else:raise ValueError('Unknown mode')
