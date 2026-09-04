#!/bin/bash
# Phase I driver fulfillment smoke: passenger orders -> driver accepts/arrives/starts/finishes -> passenger pays
set -e
BASE="http://127.0.0.1:9000"
STAMP=$(date +%s)
USER_EMAIL="ride-user-$STAMP@test.local"
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

echo "== 1. admin login (password already reset by smoke-ticket) =="
ADMIN_TOKEN=$(curl -s -X POST "$BASE/api/auth/login/password" -H "Content-Type: application/json" \
  -d "{\"login\":\"$ADMIN_EMAIL\",\"password\":\"$PASS\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
if [ -z "$ADMIN_TOKEN" ]; then
  echo "  admin login failed, resetting password..."
  for attempt in 1 2 3 4; do
    HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/auth/email-code" \
      -H "Content-Type: application/json" \
      -d "{\"email\":\"$ADMIN_EMAIL\",\"scene\":\"RESET_PASSWORD\"}")
    if [ "$HTTP" = "200" ]; then break; fi
    sleep 65
  done
  CODE=$(fetch_code "$ADMIN_EMAIL")
  curl -s -X POST "$BASE/api/auth/password/reset" -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"code\":\"$CODE\",\"newPassword\":\"$PASS\"}" > /dev/null
  ADMIN_TOKEN=$(curl -s -X POST "$BASE/api/auth/login/password" -H "Content-Type: application/json" \
    -d "{\"login\":\"$ADMIN_EMAIL\",\"password\":\"$PASS\"}" \
    | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
fi
echo "admin_token_len=${#ADMIN_TOKEN}"

echo "== 2. create driver account =="
DRIVER_USERNAME="driver$STAMP"
curl -s -X POST "$BASE/api/users/admin/create" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$DRIVER_USERNAME\",\"password\":\"$PASS\",\"role\":\"DRIVER\"}" | head -c 120
echo
DRIVER_TOKEN=$(curl -s -X POST "$BASE/api/auth/login/password" -H "Content-Type: application/json" \
  -d "{\"login\":\"$DRIVER_USERNAME#DRIVER\",\"password\":\"$PASS\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
echo "driver_token_len=${#DRIVER_TOKEN}"

echo "== 3. passenger registers and creates order =="
curl -s -X POST "$BASE/api/auth/email-code" -H "Content-Type: application/json" \
  -d "{\"email\":\"$USER_EMAIL\",\"scene\":\"REGISTER\"}" > /dev/null
CODE=$(fetch_code "$USER_EMAIL")
USER_TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H "Content-Type: application/json" \
  -d "{\"email\":\"$USER_EMAIL\",\"code\":\"$CODE\",\"password\":\"$PASS\",\"username\":\"rideuser$STAMP\",\"role\":\"USER\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
ORDER_ID=$(curl -s -X POST "$BASE/api/orders" -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"vehicleType":1,"isExpedited":0,"isReservation":0,"startAddress":"Start St","startLat":31.2304,"startLng":121.4737,"endAddress":"End St","endLat":31.3,"endLng":121.5}')
echo "order_id=$ORDER_ID"

echo "== 4. driver trip flow =="
curl -s -X POST "$BASE/api/orders/driver/accept" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H "Content-Type: application/json" -d "{\"orderId\":\"$ORDER_ID\"}" -w " accept=%{http_code}\n" -o /dev/null
curl -s -X POST "$BASE/api/orders/driver/arrive" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H "Content-Type: application/json" -d "{\"orderId\":\"$ORDER_ID\"}" -w " arrive=%{http_code}\n" -o /dev/null
curl -s -X POST "$BASE/api/orders/driver/start" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H "Content-Type: application/json" -d "{\"orderId\":\"$ORDER_ID\"}" -w " start=%{http_code}\n" -o /dev/null
BILL=$(curl -s -X POST "$BASE/api/orders/driver/finish" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"orderId\":\"$ORDER_ID\",\"endLat\":31.3,\"endLng\":121.5,\"endAddress\":\"End St\",\"realPolyline\":\"121.4737,31.2304;121.5,31.3\"}")
echo "bill=$BILL"

echo "== 5. passenger pays =="
curl -s -X POST "$BASE/api/orders/$ORDER_ID/pay" -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" -d '{"payChannel":1,"tradeNo":"smoke-trade-1"}' -w " pay=%{http_code}\n" -o /dev/null

echo "== 6. verify final states =="
curl -s "$BASE/api/orders/$ORDER_ID" -H "Authorization: Bearer $USER_TOKEN" | python -c "
import json,sys
d=json.load(sys.stdin)
print('status=%s realPrice=%s realDistance=%s estRoute_len=%s' % (d.get('orderStatus'), d.get('realPrice'), d.get('realDistance'), len(d.get('estRoute') or '')))
"
curl -s "$BASE/api/orders/driver/tasks?isFinished=false" -H "Authorization: Bearer $DRIVER_TOKEN" | python -c "
import json,sys
d=json.load(sys.stdin)
print('driver_pending_tasks=%d' % len(d))
"

echo "SMOKE DONE"
