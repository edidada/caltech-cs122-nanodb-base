"""Only verifies provenance, not SQL execution."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[1]
m=json.loads((root/'schemas/tpch/query-source.json').read_text())
assert {e['path'] for e in m['files']}=={f'queries/{n}.sql' for n in range(1,23)}
for e in m['files']:
    b=(root/'schemas/tpch/query-templates'/Path(e['path']).name).read_bytes()
    assert len(b)==e['bytes'] and hashlib.sha256(b).hexdigest()==e['sha256'],e['path']
print('All 22 original template hashes verified; query execution remains pending')
