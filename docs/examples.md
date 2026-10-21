# Worked examples

**Every figure on this page was computed by running the code in
`src/main/java/.../domain`, not written by hand.** The file is generated, so it
cannot drift away from the implementation — if a fee rate changes, these
numbers change with it.

Amounts are in paise unless a decimal point says otherwise. The client starts
with 5,00,000.00 funded, and TCS is marked at 3050.00.

---

## 1 · A purchase

```http
POST /api/v1/trades
Content-Type: application/json
```
```json
{
  "tradeId": "T-1",
  "clientId": "ARY",
  "symbol": "TCS",
  "side": "BUY",
  "quantity": 40,
  "price": "3000.00",
  "businessDate": "2026-03-02"
}
```

### What it costs

| charge | rate | paise | rupees |
|---|---|---:|---:|
| brokerage | 0.03% of turnover, capped at 20.00 | 2000 | 20.00 |
| securities transaction tax | 0.1% | 12000 | 120.00 |
| exchange transaction | 0.00297% | 356 | 3.56 |
| regulator turnover | 0.0001% | 12 | 0.12 |
| stamp duty | 0.015%, purchases only | 1800 | 18.00 |
| GST | 18% of brokerage + exchange + regulator | 426 | 4.26 |
| **total** | | **16594** | **165.94** |

Turnover is 40 x 3000.00 = 12,000,000 paise.

### The journal entry it produces

| account | asset | amount |
|---|---|---:|
| `CLIENT:ARY:CASH` | INR | -12,016,594 |
| `SUSPENSE` | INR | 12,000,000 |
| `BROKERAGE_INCOME` | INR | 2,000 |
| `TAX_PAYABLE` | INR | 14,226 |
| `EXCHANGE_FEES` | INR | 368 |
| `CLIENT:ARY:SEC` | EQ:TCS | 40 |
| `SUSPENSE` | EQ:TCS | -40 |

**INR nets to 0** · **EQ:TCS nets to 0**

### Response — `201 Created`

```json
{
  "tradeId": "T-1",
  "entryId": 1,
  "alreadyPosted": false,
  "attempts": 1,
  "charges": {
    "brokerage": "20.00",
    "securities transaction tax": "120.00",
    "exchange transaction": "3.56",
    "regulator turnover": "0.12",
    "stamp duty": "18.00",
    "GST": "4.26"
  },
  "totalCharges": "165.94",
  "margin": {
    "cash": "379834.06",
    "marketValue": "122000.00",
    "equity": "501834.06",
    "requirement": "24400.00",
    "headroom": "477434.06",
    "solvent": true
  }
}
```

---

## 2 · A sale

Charges work against the client on a sale as well as a purchase, so they come
**out of** the proceeds rather than being added to them. Stamp duty is the one
charge that applies to purchases only.

```http
POST /api/v1/trades
Content-Type: application/json
```
```json
{
  "tradeId": "T-2",
  "clientId": "ARY",
  "symbol": "TCS",
  "side": "SELL",
  "quantity": 10,
  "price": "3100.00",
  "businessDate": "2026-03-02"
}
```

| charge | rate | paise | rupees |
|---|---|---:|---:|
| brokerage | 0.03% of turnover, capped at 20.00 | 930 | 9.30 |
| securities transaction tax | 0.1% | 3100 | 31.00 |
| exchange transaction | 0.00297% | 92 | 0.92 |
| regulator turnover | 0.0001% | 3 | 0.03 |
| stamp duty | 0.015%, purchases only | 0 | 0.00 |
| GST | 18% of brokerage + exchange + regulator | 184 | 1.84 |
| **total** | | **4309** | **43.09** |

Turnover is 10 x 3100.00 = 3,100,000 paise.

| account | asset | amount |
|---|---|---:|
| `CLIENT:ARY:CASH` | INR | 3,095,691 |
| `SUSPENSE` | INR | -3,100,000 |
| `BROKERAGE_INCOME` | INR | 930 |
| `TAX_PAYABLE` | INR | 3,284 |
| `EXCHANGE_FEES` | INR | 95 |
| `CLIENT:ARY:SEC` | EQ:TCS | -10 |
| `SUSPENSE` | EQ:TCS | 10 |

**INR nets to 0** · **EQ:TCS nets to 0**

Proceeds of 31000.00 less charges of 43.09 leaves 30956.91 reaching the account.

---

## 3 · The same trade, submitted twice

A client retrying after a timeout, or Kafka redelivering a record whose offset
never committed, asks for the same thing twice and gets the same answer twice.
Not a `409` — returning an error would push deduplication back onto every
caller, which is the problem an idempotency key exists to remove.

```http
POST /api/v1/trades        # identical body to example 1

HTTP/1.1 200 OK
```
```json
{
  "tradeId": "T-1",
  "entryId": 12,
  "alreadyPosted": true,
  "attempts": 0,
  "charges": {},
  "totalCharges": "0.00",
  "margin": null
}
```

The entry id is the one from the original posting. The cash moved once.
`ConcurrentSettlementIT.concurrentRedeliveryPostsOnce` fires this from twenty
threads at once and asserts exactly one entry exists afterwards.

---

## 4 · A trade that would breach margin

An account funded with only 1000.00 tries to buy 1,000 shares. The check runs **before** the
transaction commits, so the trade never happens rather than happening and being
unwound.

```
equity      = cash + marked position
            =      -3002582.28 + 3050000.00  =  47417.72
requirement = 20% of 3050000.00                  =  610000.00

            47417.72  <  610000.00   -> refused
```

```http
HTTP/1.1 409 Conflict
```
```json
{
  "code": "MARGIN_BREACH",
  "message": "posting would breach maintenance margin on CLIENT:THIN:CASH: equity INR 47417.72 against a requirement of INR 610000.00",
  "details": [
    "account CLIENT:THIN:CASH",
    "equity 47417.72",
    "requirement 610000.00"
  ]
}
```

---

## 5 · A malformed request

Every field that failed is named, so a caller can fix all of them at once
rather than discovering them one round trip at a time.

```json
{
  "tradeId": "",
  "clientId": "not a client id",
  "symbol": "tcs",
  "quantity": -5,
  "price": "3000.001"
}
```
```http
HTTP/1.1 400 Bad Request
```
```json
{
  "code": "VALIDATION_FAILED",
  "message": "the request was not valid",
  "fieldErrors": {
    "tradeId":  "tradeId is required \u2014 it is the idempotency key",
    "clientId": "clientId must be 1-32 characters of A-Z, 0-9, _ or -",
    "symbol":   "symbol must be a venue code",
    "quantity": "quantity must be positive; direction is carried by side",
    "price":    "price must be a positive decimal with at most two places"
  }
}
```

A price of `3000.001` is rejected rather than rounded. A caller passing
sub-paise precision has a unit confusion, and silently resolving it would put a
wrong number in the ledger.

---

## 6 · The statement after both trades

```http
GET /api/v1/accounts/ARY/statement
```
```json
{
  "clientId": "ARY",
  "cash": "410790.97",
  "positions": { "TCS": 30 },
  "lines": [ ... every movement above, newest first ... ]
}
```

The lines are not decoration. Because the balance is folded from exactly those
entries rather than read from a stored figure, adding them up reproduces the
closing cash — and if it ever did not, the ledger would be wrong in a way worth
finding out about. `LedgerPostingIT.statementReconciles` asserts it.

```
opening                            500000.00
BUY  40 TCS @ 3,000.00            -120165.94
SELL 10 TCS @ 3,100.00              30956.91
                              --------------
closing                            410790.97
```

```http
GET /api/v1/accounts/ARY/margin?asOf=2026-03-02
```
```json
{
  "cash": "410790.97",
  "marketValue": "91500.00",
  "equity": "502290.97",
  "requirement": "18300.00",
  "headroom": "483990.97",
  "solvent": true
}
```

---

## Why amounts are strings

Every amount crosses the API as a decimal string, never a JSON number. A JSON
number is parsed as binary floating point by most clients, and a consumer
turning `1234.56` into a `double` has reintroduced precisely the error the
ledger is built to prevent. Share counts are integers and stay integers.
