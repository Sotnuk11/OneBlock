"""Artifact/source guards for the presentation candidate. Does not connect to any database."""
from pathlib import Path
import hashlib,json,re,zipfile,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
BACK=ROOT/'build/backups/visual-polish-20261027'
OUT=ROOT/'build/reports/visual-polish-verification.json'
def sha(p,algorithm='sha256'):return hashlib.new(algorithm,p.read_bytes()).hexdigest()
def config(p):return json.loads('\n'.join(line for line in p.read_text(encoding='utf-8-sig').splitlines() if not line.lstrip().startswith('#')))
def artifact(p):return {'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p)}
items=config(ROOT/'src/main/resources/items.yml');old=config(BACK/'src/main/resources/items.yml')
assert items.keys()==old.keys()
changed=[]
for key,definition in items['items'].items():
    prior=old['items'][key]
    assert {k:v for k,v in definition.items() if k!='name'}=={k:v for k,v in prior.items() if k!='name'},key
    assert definition['name'][2:]==prior['name'][2:],key
    assert definition['name'][:2]=={'COMMON':'&f','UNCOMMON':'&a','RARE':'&9','EPIC':'&d','LEGENDARY':'&6'}[definition.get('rarity','COMMON')]
    if definition['name']!=prior['name']:changed.append(key)
assert {k:v for k,v in items.items() if k!='items'}=={k:v for k,v in old.items() if k!='items'}
mine=config(ROOT/'src/main/resources/dungeon.yml');before=config(BACK/'src/main/resources/dungeon.yml')
assert {k:v for k,v in mine.items() if k not in ['messages','presentation']}=={k:v for k,v in before.items() if k not in ['messages','presentation']}
untouched=[]
for area in ['storage','market','trade']:
    for p in (ROOT/f'src/main/kotlin/com/alytanta/oneblock/{area}').rglob('*.kt'):
        assert sha(p)==sha(BACK/p.relative_to(ROOT)),p
        untouched.append(str(p.relative_to(ROOT)))
for p in (ROOT/'src/main/resources/menus').rglob('commands.yml'):
    assert sha(p)==sha(BACK/p.relative_to(ROOT)),p
jar=ROOT/'build/libs/one-block-1.0.0-rc.3-polish.1.jar'
with zipfile.ZipFile(jar) as z:
    checked=[]
    for p in (ROOT/'src/main/resources').rglob('*'):
        if not p.is_file():continue
        key=p.relative_to(ROOT/'src/main/resources').as_posix()
        wanted=p.read_bytes()
        if key=='plugin.yml':wanted=wanted.replace(b'${version}',b'1.0.0-rc.3-polish.1').replace(b'\r\n',b'\n')
        assert z.read(key)==wanted,key
        checked.append(key)
    assert not any('/smoke/' in n or '/tools/' in n or n.endswith('Test.class') for n in z.namelist())
pack=ROOT/'build/resource-pack/oneblock-26.1.2.zip'
assert sha(pack,'sha1')==(ROOT/'build/resource-pack/oneblock-26.1.2.zip.sha1').read_text().strip()
assert sha(pack)==sha(ROOT/'build/visual-client/game/resourcepacks/oneblock-26.1.2.zip')
tests=[ET.parse(p).getroot() for p in (ROOT/'build/reports/polish-full-suite-final').rglob('TEST-*.xml')]
totals={k:sum(int(t.attrib.get(k,0)) for t in tests) for k in ['tests','failures','errors','skipped']}
assert totals['tests']>=443 and not any(totals[k] for k in ['failures','errors','skipped'])
staged=json.loads((ROOT/'build/visual-polish/install/manifest.json').read_text(encoding='utf-8'))
for path,value in staged['read-only-sources'].items():assert sha(Path(path.replace('\\','/')))==value,path
for row in json.loads((ROOT/'build/visual-polish-server-before.json').read_text(encoding='utf-8-sig')):assert sha(Path(row['Path'].replace('\\','/'))).upper()==row['Hash'],row['Path']
log=(ROOT/'build/polish-final.log').read_text(encoding='utf-8',errors='replace')
assert 'BUILD SUCCESSFUL' in log
native=[]
for match in re.findall(r'Isolated Folia result: (.+result\.txt)',log):
    path=Path(match.strip().replace('\\','/'));result=path.read_text(encoding='utf-8');assert result.startswith('PASS:')
    assert sha(path.parent/'plugins/one-block.jar')=='6941623730ae6601e6a223a28ec4a78b614130d6161ad03594bcc224c32fa3b0',path
    native.append({'path':str(path),'result':result.strip(),'jar-sha256':sha(path.parent/'plugins/one-block.jar')})
assert len(native)==3,native
lighting_log=(ROOT/'build/polish-lighting-final.log').read_text(encoding='utf-8',errors='replace')
assert 'BUILD SUCCESSFUL' in lighting_log
lighting_native=[]
for match in re.findall(r'Isolated Folia result: (.+result\.txt)',lighting_log):
    path=Path(match.strip().replace('\\','/'));result=path.read_text(encoding='utf-8');assert result.startswith('PASS:')
    assert sha(path.parent/'plugins/one-block.jar')==sha(jar),path
    lighting_native.append({'path':str(path),'result':result.strip()})
assert len(lighting_native)==2,lighting_native
lighting_tests=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')]
assert lighting_tests and all(int(t.attrib.get('failures',0))==int(t.attrib.get('errors',0))==0 for t in lighting_tests)
graphical=json.loads((ROOT/'build/reports/visual-polish-graphical.json').read_text(encoding='utf-8'))
assert graphical['final-jar-sha256']==sha(jar)
report={'version':'1.0.0-rc.3-polish.1','jar':artifact(jar),'pack':artifact(pack)|{'sha1':sha(pack,'sha1')},
        'suite':totals|{'classes':len(tests)},'exact-bundled-resources':len(checked),'native':native,
        'item-name-colour-updates':changed,'item-mechanics-and-IDs-preserved':True,'mine-balance-preserved':True,
        'unchanged-storage-market-trade-sources':len(untouched),'routes-unchanged':True,
        'staged-presentation-leaves':len(staged['changed-presentation-leaves']),'preserved-custom-leaves':staged['preserved-custom-leaves-review'],
        'server-public-files-unchanged':len(staged['read-only-sources']),
        'lighting-followup':{'tests':sum(int(t.attrib['tests']) for t in lighting_tests),'native':lighting_native},
        'graphical-review':graphical,
        'server-installed':False,'live-database-accessed':False}
OUT.parent.mkdir(parents=True,exist_ok=True);OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'tests':report['suite'],'jar':report['jar'],'pack':report['pack'],'native-runs':len(native)},indent=2))
