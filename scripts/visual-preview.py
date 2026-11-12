"""Render exported templates for review. Flat colours are schematic, not client textures."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'Нотатки/Зображення/polish'
OUT.mkdir(parents=True, exist_ok=True)
FONT = 'C:/Windows/Fonts/arial.ttf'
COLORS = {'grass_block':'#70905b','moss_block':'#647e48','stone_bricks':'#929a94',
          'mossy_stone_bricks':'#7a8b73','smooth_quartz':'#e0dbca','smooth_stone':'#aab0ab',
          'polished_andesite':'#8b9c9a','spruce_planks':'#97754b','stripped_spruce_log':'#715333',
          'spruce_log':'#574530','dark_prismarine':'#286064','light_blue_stained_glass':'#88bcc4',
          'sea_lantern':'#def3ce','glowstone':'#edc27c','oak_leaves':'#517746',
          'deepslate_bricks':'#4e5360','deepslate_tiles':'#444753','cracked_deepslate_bricks':'#656270',
          'polished_deepslate':'#696c79','andesite':'#94979c','chiseled_deepslate':'#89929d',
          'lantern':'#f5c674','iron_bars':'#8f9ca2','hay_block':'#cfad58','bookshelf':'#85674a',
          'rail':'#9d8b65','anvil':'#434954','iron_ore':'#b8a091'}

def render(blocks, filename, title, subtitle, mine=False, anchors=None):
    data={}
    for b in blocks:
        p=b.get('point',b);x,y,z=p['x'],p['y'],p['z']
        y-=64 if mine else 100
        if mine and (y>=7 or z in (32,44) or x==104):continue
        data[x,y,z]=b['data'].split(':')[-1].split('[')[0]
    s=7 if mine else 8
    def project(x,y,z):return ((x-z)*s,(x+z)*s/2-y*s)
    points=[project(x,y,z) for x,y,z in data]
    minx,miny=min(x for x,y in points),min(y for x,y in points)
    maxx,maxy=max(x for x,y in points),max(y for x,y in points)
    width=int(maxx-minx+150);height=int(maxy-miny+220)
    im=Image.new('RGB',(max(width,1250),height),'#f3f0e8');draw=ImageDraw.Draw(im)
    def p(x,y,z):
        a,b=project(x,y,z);return (a-minx+75,b-miny+125)
    def shade(c,f):return tuple(int(int(c[i:i+2],16)*f) for i in (1,3,5))
    for (x,y,z),kind in sorted(data.items(),key=lambda row:(sum((row[0][0],row[0][2])),row[0][1])):
        c=COLORS.get(kind,'#8e816e')
        if 'sign' in kind or kind in ('rail','torch','lever','stone_button'):
            a,b=p(x,y,z);draw.rectangle((a-2,b-3,a+3,b+2),fill=c);continue
        if (x+1,y,z) not in data:
            draw.polygon([p(x+1,y,z),p(x+1,y,z+1),p(x+1,y+1,z+1),p(x+1,y+1,z)],fill=shade(c,.68))
        if (x,y,z+1) not in data:
            draw.polygon([p(x,y,z+1),p(x+1,y,z+1),p(x+1,y+1,z+1),p(x,y+1,z+1)],fill=shade(c,.83))
        if (x,y+1,z) not in data:
            draw.polygon([p(x,y+1,z),p(x+1,y+1,z),p(x+1,y+1,z+1),p(x,y+1,z+1)],fill=c)
    labels={'spawn':'Площа','merchant':'Скупник','bazaar':'Базар','auction':'Аукціон','workshop':'Майстерня','guide':'Провідник','dungeon1':'Стежка до шахти'}
    for anchor in anchors or []:
        if anchor['role'] not in labels:continue
        point=anchor['point'];a,b=p(point['x'],point['y']-100,point['z'])
        draw.ellipse((a-5,b-5,a+5,b+5),fill='#e1a650',outline='#ffffff',width=2)
        draw.text((a+9,b-10),labels[anchor['role']],font=ImageFont.truetype(FONT,14),fill='#243e41',stroke_width=2,stroke_fill='#f3f0e8')
    draw.text((35,22),title,font=ImageFont.truetype(FONT,29),fill='#243e41')
    draw.text((35,65),subtitle,font=ImageFont.truetype(FONT,18),fill='#52676a')
    draw.text((35,height-38),'Схема з фактичного blueprint · пласкі кольори · це не скриншот Minecraft',font=ImageFont.truetype(FONT,17),fill='#52676a')
    im.save(OUT/filename)

hub=json.loads((ROOT/'build/visual-polish/hub-plan.json').read_text())
render(hub['blocks'],'hub-after.png','OneBlock · поселення над хмарами','Новий шаблон: зелені двори, тепле світло, різні фасади сервісів',anchors=hub['anchors'])
baseline=ROOT/'build/backups/visual-polish-20261027/hub-before.json'
if baseline.exists():
    old=json.loads(baseline.read_text())
    render(old['blocks'],'hub-before.png','OneBlock · попередній шаблон','Однакова проєкція для порівняння · дані попереднього ізольованого native build')
mine=json.loads((ROOT/'build/visual-polish/mine-plan.json').read_text())
render(mine,'mine-cutaway.png','Покинута шахта · три кімнати','Вантажна галерея → майстерня колій → зала вартового · дах і передні стіни приховані',True)
before=ROOT/'build/backups/visual-polish-20261027/items-preview.png'
after=ROOT/'build/resource-pack/items-preview.png'
a,b=Image.open(before).convert('RGB'),Image.open(after).convert('RGB')
im=Image.new('RGB',(a.width*2+40,a.height+70),'#f3f0e8');d=ImageDraw.Draw(im)
d.text((16,15),'До',font=ImageFont.truetype(FONT,25),fill='#243e41');d.text((a.width+36,15),'Після',font=ImageFont.truetype(FONT,25),fill='#243e41')
im.paste(a,(10,60));im.paste(b,(a.width+30,60));im.save(OUT/'items-before-after.png')
print('Rendered hub, mine cutaway and original item atlas comparison')
