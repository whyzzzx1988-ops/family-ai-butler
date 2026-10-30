# -*- coding: utf-8 -*-
"""
真钥匙链路一键验证（第二周 W1/W2 + 第三/四周学习加工与出题）。

与 scripts/e2e-api-test.ps1 的分工：那份验无钥匙时的结构与降级；这份验配上
真实 DEEPSEEK_API_KEY（及可选 DASHSCOPE_API_KEY）后模型链路的真实产出质量位：
纪要三段结构、待办候选线索、学习结构化 detail、出题非兜底、账本落账。

前置：
  1) 后端已启动且环境里有真实 Key（PowerShell: $env:DEEPSEEK_API_KEY="sk-..." 后再启动应用）
  2) local 种子账号可用（yuge / dev123456）；如已改种子，改下面 LOGIN 常量
  3) 可选： --wav 一段真人语音（单声道 wav/mp3 等），验 ASR 真转写；不给则跳过

用法： python scripts/e2e-realkey-check.py [--base http://localhost:8080] [--wav test.wav]
退出码 = 失败条数（0 为全过；SKIP 不计失败）。
"""
import argparse, io, json, sys, urllib.request, urllib.error
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

ap = argparse.ArgumentParser()
ap.add_argument('--base', default='http://localhost:8080')
ap.add_argument('--wav', default='')
args = ap.parse_args()
BASE = args.base.rstrip('/')
TZ = ZoneInfo('Asia/Shanghai')
TOM = (datetime.now(TZ).date() + timedelta(days=1)).isoformat()

LOGIN_ID, LOGIN_PW = 'yuge', 'dev123456'
passed = failed = 0

def check(name, cond, detail=''):
    global passed, failed
    if cond:
        passed += 1; print(f'PASS | {name} | {detail}')
    else:
        failed += 1; print(f'FAIL | {name} | {detail}')

def skip(name, why):
    print(f'SKIP | {name} | {why}')

def call(method, path, body=None, token=None, raw=None, ctype=None):
    req = urllib.request.Request(BASE + path, method=method)
    data = raw
    if body is not None:
        data = json.dumps(body).encode('utf-8')
        req.add_header('Content-Type', 'application/json')
    if ctype:
        req.add_header('Content-Type', ctype)
    if token:
        req.add_header('Authorization', token)
    try:
        with urllib.request.urlopen(req, data=data, timeout=120) as r:
            txt = r.read().decode('utf-8')
            try: return r.status, json.loads(txt)
            except Exception: return r.status, {'_text': txt}
    except urllib.error.HTTPError as e:
        txt = e.read().decode('utf-8')
        try: return e.code, json.loads(txt)
        except Exception: return e.code, {'_text': txt}

def as_detail(view):
    d = view.get('detail')
    if isinstance(d, str):
        try: return json.loads(d)
        except Exception: return {}
    return d or {}

# ---------- 登录与网关 ----------
s, b = call('POST', '/api/auth/login', {'identifier': LOGIN_ID, 'password': LOGIN_PW})
T = b.get('tokenValue', '')
check('登录（种子账号密码）', s == 200 and bool(T), f'status={s}')
if not T:
    print('登录失败，后续无从验起，先修登录。'); sys.exit(1)

s, usage0 = call('GET', '/api/llm/usage', token=T)
calls0 = usage0.get('totalCalls', 0) if isinstance(usage0, dict) else 0

s, b = call('POST', '/api/llm/chat', {'prompt': '用一句话介绍你自己'}, token=T)
check('D7 chat 真调用 -> 200 带 reply 与 logId', s == 200 and bool(b.get('reply')) and bool(b.get('logId')),
      f'status={s}, reply={str(b.get("reply"))[:30]}')

# ---------- W1 纪要 ----------
s, b = call('POST', '/api/inbox/text',
            {'text': '今天项目周会定了两件事：一是首页改版下周三上线，二是张三负责联系设计师确认图标，预算先按五万走，后续再对账。'},
            token=T)
E = b.get('id')
s, b = call('POST', '/api/work/minutes', {'eventId': E}, token=T)
note_view = b.get('note', b) if isinstance(b, dict) else {}
md = note_view.get('contentMd', '') if isinstance(note_view, dict) else ''
cands = note_view.get('todoCandidates', []) if isinstance(note_view, dict) else []
N = note_view.get('id') if isinstance(note_view, dict) else None
check('W1 纪要生成 -> 201 三段结构齐', s == 201 and all(h in md for h in ('## 背景', '## 结论', '## 讨论要点')),
      f'status={s}')
check('W1 纪要带 llmLogId（落账）', bool(b.get('llmLogId') or note_view.get('llmLogId')), f"body keys={list(b.keys())[:8]}")
check('W2 候选非空且带原文线索（ownerHint/dueHint）',
      len(cands) >= 1 and any(c.get('ownerHint') or c.get('dueHint') for c in cands),
      f'n={len(cands)}, first={str(cands[0])[:100] if cands else None}')
s, b = call('POST', '/api/work/minutes', {'eventId': E}, token=T)
check('W1 同一事件再生成 -> 409', s == 409, f'status={s}')

# W2 确认流
if N and cands:
    s, b = call('POST', f'/api/work/minutes/{N}/todos/confirm',
                {'items': [{'index': 0, 'dueAt': f'{TOM}T10:00:00+08:00'}]}, token=T)
    created = b.get('createdTodos', []) if isinstance(b, dict) else []
    check('W2 确认候选 -> 201 且 sourceEventId=来源事件',
          s == 201 and len(created) == 1 and created[0].get('sourceEventId') == E,
          f'status={s}')
    s, b = call('POST', f'/api/work/minutes/{N}/todos/confirm', {'items': [{'index': 0}]}, token=T)
    check('W2 同下标再确认 -> 409', s == 409, f'status={s}')
else:
    skip('W2 确认流', '纪要或候选为空，先看上面 FAIL')

# W1 模板（weekly）
s, b = call('POST', '/api/inbox/text',
            {'text': '本周完成了支付联调和首页联调，下周计划上线灰度，风险是第三方接口限流还没压测。'}, token=T)
E2 = b.get('id')
s, b = call('POST', '/api/work/minutes', {'eventId': E2, 'template': 'weekly'}, token=T)
nv = b.get('note', b) if isinstance(b, dict) else {}
md2 = nv.get('contentMd', '') if isinstance(nv, dict) else ''
check('N5 weekly 模板 -> 段落为 本周进展/下周计划/风险与阻塞 且 template=weekly',
      s == 201 and all(h in md2 for h in ('## 本周进展', '## 下周计划', '## 风险与阻塞')) and nv.get('template') == 'weekly',
      f'status={s}, template={nv.get("template")}')

# ---------- 学习加工与出题 ----------
s, members = call('GET', '/api/families/members', token=T)
C = next((m['id'] for m in members if m.get('role') == 'child'), None) if isinstance(members, list) else None
s, b = call('POST', '/api/learn/records',
            {'kind': 'word', 'subject': '英语', 'content': '今天学了单词 abandon', 'childMemberId': C}, token=T)
view = b.get('record', b) if isinstance(b, dict) else {}
det = as_detail(view)
check('L 单词加工 -> detail 含 headword/meaning（真模型结构化）',
      s == 201 and bool(det.get('headword')) and bool(det.get('meaning')),
      f'status={s}, detail keys={list(det.keys())}')
check('L 加工带 llmLogId', bool(b.get('llmLogId')), '')
s, b = call('POST', '/api/inbox/text', {'text': '今天数学做错了一道分数加法题，总是通分出错'}, token=T)
L = b.get('id')
s, b = call('POST', f'/api/inbox/{L}/to-learn', {'subject': '数学', 'childMemberId': C}, token=T)
view = b.get('record', b) if isinstance(b, dict) else {}
det = as_detail(view)
check('L 错题加工 -> detail 含 knowledgePoints/analysis',
      s == 201 and (det.get('knowledgePoints') or det.get('analysis')),
      f'status={s}, detail keys={list(det.keys())}')

s, b = call('GET', f'/api/learn/review/today?memberId={C}', token=T)
due = b if isinstance(b, list) else b.get('items', [])
if due:
    s, b = call('POST', '/api/learn/review/quiz', {'memberId': C, 'limit': 5}, token=T)
    note = (b.get('note') or '') if isinstance(b, dict) else ''
    check('R 出题为真模型题（无兜底注记）且带 llmLogId',
          s == 200 and '兜底' not in note and bool(b.get('llmLogId')),
          f'status={s}, note={note[:40]}')
else:
    skip('R 出题真模型验', '当前无到期条目；先在 H2 控制台执行 '
         'update learn_record set next_review_at = current_timestamp where id = <单词记录id>; 再重跑本脚本')

# ---------- 账本对账 ----------
s, usage1 = call('GET', '/api/llm/usage', token=T)
calls1 = usage1.get('totalCalls', 0) if isinstance(usage1, dict) else 0
check('D7 账本：本轮调用数增加且有成本', calls1 > calls0, f'calls {calls0} -> {calls1}')

# ---------- ASR（可选） ----------
if args.wav:
    with open(args.wav, 'rb') as f:
        wav = f.read()
    boundary = '----realkeyboundary'
    raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="test.wav"\r\n'
           f'Content-Type: audio/wav\r\n\r\n').encode() + wav + f'\r\n--{boundary}--\r\n'.encode()
    s, b = call('POST', '/api/inbox/voice', token=T, raw=raw, ctype=f'multipart/form-data; boundary={boundary}')
    check('ASR 真转写 -> text 非空且 status 非 asr_unconfigured',
          s in (200, 201, 202) and bool(b.get('text')) and b.get('status') != 'asr_unconfigured',
          f'status={s}, text={str(b.get("text"))[:40]}, ev_status={b.get("status")}')
else:
    skip('ASR 真转写', '未传 --wav；百炼账户恢复后用一段真人单声道录音重跑')

print(f'\n== REALKEY RESULT: {passed} passed, {failed} failed ==')
sys.exit(failed)
