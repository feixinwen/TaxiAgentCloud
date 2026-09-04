#!/bin/bash
# Phase G order service smoke test: register -> login -> estimate -> create -> detail -> page -> ongoing -> cancel
set -e
BASE="http://127.0.0.1:9000"
STAMP=$(date +%s)
EMAIL="order-smoke-$STAMP@test.local"
USERNAME="ordersmoke$STAMP"
CODE=""

echo "== 1. request email code =="
curl -s -X POST "$BASE/api/auth/email-code" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"scene\":\"REGISTER\"}" | head -c 300
echo

echo "== 2. fetch code from mailpit =="
for i in $(seq 1 10); do
  CODE=$(curl -s "http://127.0.0.1:8025/api/v1/messages" | python -c "
import json,sys,re,urllib.request
msgs=json.load(sys.stdin)
for m in msgs['messages']:
    tos = m.get('To') or []
    if any('$EMAIL' in str(t.get('Address','')) for t in tos):
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
echo "code=$CODE"

echo "== 3. register =="
REG=$(curl -s -X POST "$BASE/api/auth/register" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"code\":\"$CODE\",\"password\":\"SmokePass123!\",\"username\":\"$USERNAME\",\"role\":\"USER\"}")
echo "$REG" | head -c 400
echo
TOKEN=$(echo "$REG" | python -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))")
echo "token_len=${#TOKEN}"

echo "== 4. estimate via gateway (real amap) =="
EST=$(curl -s -X POST "$BASE/api/orders/estimate" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"startLat":31.2304,"startLng":121.4737,"endLat":31.3,"endLng":121.5,"vehicleType":1,"isExpedited":0}')
echo "$EST"
PRICE=$(echo "$EST" | python -c "import json,sys; print(json.load(sys.stdin).get('estPrice',''))")
echo "est_price=$PRICE"

echo "== 5. create order =="
ORDER_ID=$(curl -s -X POST "$BASE/api/orders" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"vehicleType":1,"isExpedited":0,"isReservation":0,"startAddress":"Start St","startLat":31.2304,"startLng":121.4737,"endAddress":"End St","endLat":31.3,"endLng":121.5}')
echo "order_id=$ORDER_ID"

echo "== 6. order detail (mongo route) =="
curl -s "$BASE/api/orders/$ORDER_ID" -H "Authorization: Bearer $TOKEN" | python -c "
import json,sys
d=json.load(sys.stdin)
print('orderId=%s status=%s estRoute_len=%s estPrice=%s safety=%s' % (d.get('orderId'), d.get('orderStatus'), len(d.get('estRoute') or ''), d.get('estPrice'), d.get('safetyCode')))
"

echo "== 7. my/ongoing =="
curl -s "$BASE/api/orders/my/ongoing" -H "Authorization: Bearer $TOKEN"
echo

echo "== 8. page =="
curl -s -X POST "$BASE/api/orders/page" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"page":1,"size":10}' | python -c "
import json,sys
d=json.load(sys.stdin)
print('total=%s records=%s' % (d.get('total'), len(d.get('records') or [])))
"

echo "== 9. cancel =="
curl -s -X POST "$BASE/api/orders/$ORDER_ID/cancel" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"cancelRole":1,"cancelReason":"smoke cancel"}'
echo

echo "== 10. detail after cancel =="
curl -s "$BASE/api/orders/$ORDER_ID" -H "Authorization: Bearer $TOKEN" | python -c "
import json,sys
d=json.load(sys.stdin)
print('status=%s cancelRole=%s cancelReason=%s realPrice=%s' % (d.get('orderStatus'), d.get('cancelRole'), d.get('cancelReason'), d.get('realPrice')))
"

echo "== 11. security check: no token -> 401, others order -> 404 =="
curl -s -o /dev/null -w "no-token=%{http_code}\n" "$BASE/api/orders/my/ongoing"
curl -s -o /dev/null -w "estimate-no-token=%{http_code}\n" -X POST "$BASE/api/orders/estimate" -H "Content-Type: application/json" -d '{}'

echo "SMOKE DONE"
