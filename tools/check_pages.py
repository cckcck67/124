"""編譯前檢查 App 內建網頁:HTML 標籤要成對、<script> 內的程式要能被解析(用 node --check)。"""
import glob, os, re, subprocess, sys, tempfile
from html.parser import HTMLParser

VOID = {'meta', 'br', 'input', 'link', 'img', 'hr', 'source'}

class Check(HTMLParser):
    def __init__(self):
        super().__init__()
        self.stack, self.errors = [], []
    def handle_starttag(self, tag, attrs):
        if tag not in VOID:
            self.stack.append(tag)
    def handle_endtag(self, tag):
        if self.stack and self.stack[-1] == tag:
            self.stack.pop()
        else:
            self.errors.append(f'</{tag}> 不成對(目前在 {self.stack[-3:]})')

bad = False
for f in sorted(glob.glob('app/src/main/assets/*.html')):
    text = open(f, encoding='utf-8').read()
    c = Check()
    c.feed(text)
    errs = c.errors + ([f'沒有關閉的標籤 {c.stack}'] if c.stack else [])
    for i, js in enumerate(re.findall(r'<script>(.*?)</script>', text, re.S)):
        with tempfile.NamedTemporaryFile('w', suffix='.js', delete=False, encoding='utf-8') as t:
            t.write(js)
        r = subprocess.run(['node', '--check', t.name], capture_output=True, text=True)
        os.unlink(t.name)
        if r.returncode != 0:
            errs.append(f'第 {i + 1} 段 <script> 語法錯誤:\n{r.stderr}')
    print(f, 'OK' if not errs else 'FAIL')
    for e in errs:
        print('  ', e)
    bad = bad or bool(errs)
sys.exit(1 if bad else 0)
