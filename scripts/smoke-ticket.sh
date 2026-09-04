#!/bin/bash
# Phase H ticket service smoke: user submits ticket -> support assigns/processes -> user confirms
set -e
BASE="http://127.0.0.1:9000"
STAMP=$(date +%s)
USER_EMAIL="ticket-user-$STAMP@test.local"
SUPPORT_EMAIL="ticket-support-$STAMP@test.local"
ADMIN_EMAIL="admin-e2e@example.com"
PASS="SmokePass123!"

fetch_code() { # $1 = email
  for i in $(seq 1 10); do
    CODE=$(curl -s "http://127.0.0.1:8025/api/v1/messages" | python -c "
import json,sys,re,urllib.request
msgs=json.load(sys.stdin)
for m in msgs['messages']:
    tos = m.get('To') or []
    if any('$1' in str(t.get('Address','')) for t in tos):
        mid=m.get('ID')
        detail=json.load(urllib.request.urlopen('http://127.0.0.1:8025/api/v1/message/'+mid))
        body=detail.get('Text','') or detail.get('HTML','')
        hit=re.search(r'(\d{6})', body)
        if hit:
            print(hit.group(1)); sys.exit(0)
print('')
" 2>/dev/null || true)
    if [ -n "$CODE" ]; then echo "$CODE"; return; fi
    sleep 2
  done
  echo ""
}

register() { # $1 = email, $2 = username
  curl -s -X POST "$BASE/api/auth/email-code" -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"scene\":\"REGISTER\"}" > /dev/null
  CODE=$(fetch_code "$1")
  curl -s -X POST "$BASE/api/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"code\":\"$CODE\",\"password\":\"$PASS\",\"username\":\"$2\",\"role\":\"USER\"}" \
    | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))"
}

reset_admin_password() {
  for attempt in 1 2 3 4; do
    HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/auth/email-code" \
      -H "Content-Type: application/json" \
      -d "{\"email\":\"$ADMIN_EMAIL\",\"scene\":\"RESET_PASSWORD\"}")
    if [ "$HTTP" = "200" ]; then break; fi
    echo "  email-code cooldown (attempt $attempt), waiting..."
    sleep 65
  done
  CODE=$(fetch_code "$ADMIN_EMAIL")
  curl -s -X POST "$BASE/api/auth/password/reset" -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"code\":\"$CODE\",\"newPassword\":\"$PASS\"}" > /dev/null
}

echo "== 1. reset admin password and login =="
reset_admin_password
ADMIN_TOKEN=$(curl -s -X POST "$BASE/api/auth/login/password" -H "Content-Type: application/json" \
  -d "{\"login\":\"$ADMIN_EMAIL\",\"password\":\"$PASS\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
echo "admin_token_len=${#ADMIN_TOKEN}"

echo "== 2. create support account =="
curl -s -X POST "$BASE/api/users/admin/create" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"support$STAMP\",\"password\":\"$PASS\",\"role\":\"SUPPORT\"}" | head -c 200
echo
SUPPORT_TOKEN=$(curl -s -X POST "$BASE/api/auth/login/password" -H "Content-Type: application/json" \
  -d "{\"login\":\"support$STAMP#SUPPORT\",\"password\":\"$PASS\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
echo "support_token_len=${#SUPPORT_TOKEN}"

echo "== 3. user registers and submits ticket =="
USER_TOKEN=$(register "$USER_EMAIL" "ticketuser$STAMP")
echo "user_token_len=${#USER_TOKEN}"
TICKET_ID=$(curl -s -X POST "$BASE/api/tickets/submit" -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ticketType":1,"priority":1,"title":"lost phone","content":"black phone on taxi"}')
echo "ticket_id=$TICKET_ID"
echo "$TICKET_ID" | grep -qE '^T[0-9]{8}1[0-9]{5}$' && echo "ticket_id format OK"

echo "== 4. support assigns and processes =="
curl -s -X POST "$BASE/api/tickets/admin/assign/$TICKET_ID" -H "Authorization: Bearer $SUPPORT_TOKEN" -o /dev/null -w "assign=%{http_code}\n"
curl -s -X POST "$BASE/api/tickets/admin/process" -H "Authorization: Bearer $SUPPORT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"ticketId\":\"$TICKET_ID\",\"actionType\":\"REPLY\",\"content\":\"we are checking\"}" -o /dev/null -w "reply=%{http_code}\n"
curl -s -X POST "$BASE/api/tickets/admin/process" -H "Authorization: Bearer $SUPPORT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"ticketId\":\"$TICKET_ID\",\"actionType\":\"RESOLVE\",\"content\":\"phone found at depot\"}" -o /dev/null -w "resolve=%{http_code}\n"

echo "== 5. user confirms and rates =="
curl -s -X POST "$BASE/api/tickets/feedback" -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"ticketId\":\"$TICKET_ID\",\"satisfied\":true,\"rating\":5,\"feedbackContent\":\"great\"}" -o /dev/null -w "feedback=%{http_code}\n"

echo "== 6. verify detail + chat + stats =="
curl -s "$BASE/api/tickets/detail/$TICKET_ID" -H "Authorization: Bearer $USER_TOKEN" | python -c "
import json,sys
d=json.load(sys.stdin)
print('status=%s processResult=%s chats=%d' % (d.get('ticketStatus'), d.get('processResult'), len(d.get('chatHistory') or [])))
last=d.get('chatHistory')[-1]
print('last_chat=%s' % last.get('content'))
"
curl -s "$BASE/api/tickets/admin/statistics" -H "Authorization: Bearer $ADMIN_TOKEN"
echo

echo "== 7. security checks =="
curl -s -o /dev/null -w "no-token=%{http_code}\n" "$BASE/api/tickets/my/unfinished"
curl -s -X POST "$BASE/api/tickets/admin/page" -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" -d '{}' -o /dev/null -w "user-to-admin=%{http_code}\n"

echo "SMOKE DONE"
