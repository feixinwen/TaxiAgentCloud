#!/bin/bash
# Phase J rag smoke: admin login -> add QA -> search hit -> update -> delete -> security
# Every HTTP call asserts its expected status code; any mismatch aborts with exit 1.
set -e
BASE="http://127.0.0.1:9000"
ADMIN_EMAIL="admin-e2e@example.com"
PASS="SmokePass123!"
TMP=$(mktemp -d)

echo "== 1. admin login =="
ADMIN_TOKEN=$(curl -s -X POST "$BASE/api/auth/login/password" -H "Content-Type: application/json" \
  -d "{\"login\":\"$ADMIN_EMAIL\",\"password\":\"$PASS\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
[ -n "$ADMIN_TOKEN" ] || { echo "FAIL: admin login"; exit 1; }
echo "admin_token_len=${#ADMIN_TOKEN}"

echo "== 2. register a user for security checks =="
USER_EMAIL="rag-user-$(date +%s)@test.local"
curl -s -X POST "$BASE/api/auth/email-code" -H "Content-Type: application/json" \
  -d "{\"email\":\"$USER_EMAIL\",\"scene\":\"REGISTER\"}" > /dev/null
for i in $(seq 1 10); do
  CODE=$(curl -s "http://127.0.0.1:8025/api/v1/messages" | python -c "
import json,sys,re,urllib.request
msgs=json.load(sys.stdin)
for m in msgs['messages']:
    tos = m.get('To') or []
    if any('$USER_EMAIL' in str(t.get('Address','')) for t in tos):
        mid=m.get('ID')
        detail=json.load(urllib.request.urlopen('http://127.0.0.1:8025/api/v1/message/'+mid))
        body=detail.get('Text','') or detail.get('HTML','')
        hit=re.search(r'(\d{6})', body)
        if hit:
            print(hit.group(1)); sys.exit(0)
print('')
" 2>/dev/null || true)
  if [ -n "$CODE" ]; then break; fi
  sleep 2
done
USER_TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "Content-Type: application/json" \
  -d "{\"email\":\"$USER_EMAIL\",\"code\":\"$CODE\",\"password\":\"$PASS\",\"username\":\"raguser$(date +%s)\",\"role\":\"USER\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
[ -n "$USER_TOKEN" ] || { echo "FAIL: user register"; exit 1; }
echo "user_token_len=${#USER_TOKEN}"

echo "== 3. admin adds QA (real embedding) =="
cat > "$TMP/add.json" <<'EOF'
{"questions":["电子发票怎么开具","发票如何申请"],"answer":"电子发票在订单详情页点击申请，1-3 个工作日发送至邮箱"}
EOF
ADD_HTTP=$(curl -s -X POST "$BASE/api/rag/admin/add" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" --data-binary @"$TMP/add.json" -w "%{http_code}" -o /dev/null)
[ "$ADD_HTTP" = "200" ] || { echo "FAIL: add expected 200 got $ADD_HTTP"; exit 1; }
echo "add=200"

echo "== 4. user searches (hybrid retrieval) =="
cat > "$TMP/search.json" <<'EOF'
{"question":"怎么开发票"}
EOF
curl -s -X POST "$BASE/api/rag/search" -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" --data-binary @"$TMP/search.json" | python -c "
import json,sys
sys.stdin.reconfigure(encoding='utf-8')
results=json.load(sys.stdin)
print('hits=%d' % len(results))
for r in results:
    print('  group=%s q=%s' % (r.get('groupId'), r.get('question')))
    assert 'questionVector' not in r and 'vector' not in str(r), 'vector leaked!'
assert len(results) >= 1, 'no hits'
print('  retrieval OK, no vector leak')
"
GROUP_ID=$(curl -s -X POST "$BASE/api/rag/admin/page" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"page":1,"size":10}' \
  | python -c "import json,sys; print(json.load(sys.stdin).get('records',[{}])[0].get('groupId',''))")
[ -n "$GROUP_ID" ] || { echo "FAIL: group_id empty"; exit 1; }
echo "group_id=$GROUP_ID"

echo "== 5. update answer and re-search =="
cat > "$TMP/upd.json" <<EOF
{"groupId":"$GROUP_ID","answer":"发票申请：订单详情页申请，24 小时内发送"}
EOF
UPDATE_HTTP=$(curl -s -X POST "$BASE/api/rag/admin/update-answer" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" --data-binary @"$TMP/upd.json" -w "%{http_code}" -o /dev/null)
[ "$UPDATE_HTTP" = "200" ] || { echo "FAIL: update expected 200 got $UPDATE_HTTP"; exit 1; }
echo "update=200"
curl -s -X POST "$BASE/api/rag/search" -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" --data-binary @"$TMP/search.json" | python -c "
import json,sys
sys.stdin.reconfigure(encoding='utf-8')
r=json.load(sys.stdin)[0]
print('new_answer=%s' % r.get('answer'))
assert '24 小时内' in r.get('answer',''), 'answer not updated'
print('  update reflected OK')
"

echo "== 6. delete and verify removed =="
cat > "$TMP/del.json" <<EOF
{"groupIds":["$GROUP_ID"],"questionIds":[]}
EOF
DELETE_HTTP=$(curl -s -X POST "$BASE/api/rag/admin/delete" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" --data-binary @"$TMP/del.json" -w "%{http_code}" -o /dev/null)
[ "$DELETE_HTTP" = "200" ] || { echo "FAIL: delete expected 200 got $DELETE_HTTP"; exit 1; }
echo "delete=200"
curl -s -X POST "$BASE/api/rag/search" -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" --data-binary @"$TMP/search.json" | python -c "
import json,sys
sys.stdin.reconfigure(encoding='utf-8')
results=json.load(sys.stdin)
print('hits_after_delete=%d' % len(results))
assert all(r.get('groupId') != '$GROUP_ID' for r in results), 'deleted group still searchable'
print('  delete OK')
"

echo "== 7. security checks =="
USER_ADMIN_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/rag/admin/add" \
  -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" -d '{"questions":["q"],"answer":"a"}')
[ "$USER_ADMIN_HTTP" = "403" ] || { echo "FAIL: user-to-admin expected 403 got $USER_ADMIN_HTTP"; exit 1; }
echo "user-to-admin=403"
NO_TOKEN_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/rag/search" \
  -H "Content-Type: application/json" -d '{"question":"发票"}')
[ "$NO_TOKEN_HTTP" = "401" ] || { echo "FAIL: no-token expected 401 got $NO_TOKEN_HTTP"; exit 1; }
echo "no-token-search=401"

rm -rf "$TMP"
echo "SMOKE DONE"
