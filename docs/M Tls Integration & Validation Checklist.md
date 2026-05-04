# mTLS Integration & Validation Checklist

## 1. Smoke / Integration Tests (Manual)

### 1.1 Public API (без mTLS)

Проверить, что публичные эндпоинты продолжают работать без клиентского сертификата:

* `POST /auth/register` → 201
* `POST /auth/login` → 200
* `GET /me/access` → 200 (с Bearer)
* `GET /me/presets` → 200
* `PUT /me/presets/...` → 200 / 412 (optimistic lock)

### 1.2 Server API (mTLS required)

#### Без client certificate

```bash
curl -k https://localhost:9443/server/match-profile/build
```

Ожидается:

* TLS handshake failure / connection reset
* или 400/403 на уровне TLS

#### С client certificate (валидный)

```bash
curl -k \
  --cert-type P12 \
  --cert "tools/mtls/out/ds-client.p12":changeit \
  -H "X-Server-Id: 10000000-0000-0000-0000-000000000001" \
  -H "Content-Type: application/json" \
  -d "{}" \
  https://localhost:9443/server/match-profile/build
```

Ожидается:

* НЕ 401/403
* 400 VALIDATION_ERROR (из-за пустого body) — это нормально

#### С неправильным fingerprint (DB mismatch)

* изменить fingerprint в БД на случайный

Ожидается:

* 403 / UNAUTHENTICATED / FORBIDDEN

#### С отключенным server_id (status != active)

Ожидается:

* 403

---

## 2. Integration Flow (End-to-End)

Повторить сценарий из `VerticalFlowIntegrationTest` через реальные HTTP-запросы:

1. Register → Login
2. Получить presets
3. Сохранить preset
4. Вызвать `/server/match-profile/build` (mTLS)
5. Вызвать `/server/runtime-preset-changes`
6. Проверить idempotency
7. Проверить конфликт ревизии

---

## 3. Проверка соответствия OpenAPI

### 3.1 HTTP статусы

Для каждого endpoint проверить:

* соответствует ли фактический статус описанному в OpenAPI
* нет ли неожиданных 500

### 3.2 Error codes

Проверить наличие кодов из OpenAPI:

* PRECONDITION_FAILED
* PRECONDITION_REQUIRED
* LOADOUT_VALIDATION_FAILED
* PRESET_REVISION_CONFLICT
* CATALOG_VERSION_NOT_SUPPORTED
* UNAUTHENTICATED

### 3.3 Security схемы

* Public API → BearerAuth
* Server API → mutualTLS

### 3.4 Headers

Проверить:

* `X-Server-Id` обязателен
* `X-Server-Certificate-Fingerprint` не используется в mTLS режиме

---

## 4. Негативные тесты

* отсутствует X-Server-Id → 401/403
* неправильный realm → 403
* неподдерживаемая catalog_version → 400/409
* повтор Idempotency-Key с другим payload → ошибка

---

## 5. Наблюдаемость (Observability)

Проверить логирование:

* успешная аутентификация сервера
* mismatch fingerprint
* отсутствие сертификата
* expired server identity

---

# Следующие этапы mTLS

## Этап 2 — Hardening

### 2.1 Удаление fallback

* удалить `X-Server-Certificate-Fingerprint` fallback
* оставить только TLS certificate

### 2.2 Certificate rotation

* поддержка смены сертификатов
* хранение нескольких fingerprint
* grace period

### 2.3 Revocation

* статус server identity = revoked
* мгновенный отказ доступа

### 2.4 Rate limiting

* лимиты на server_id

---

## Этап 3 — Production readiness

### 3.1 Separate network

* private port доступен только из internal сети

### 3.2 Ingress / Proxy

* mTLS termination на ingress (опционально)
* проксирование client cert в backend

### 3.3 Secret management

* keystore/truststore из Vault/KMS
* не хранить в repo

### 3.4 Monitoring

* метрики:

  * mTLS failures
  * auth failures
  * certificate expiry

---

## Этап 4 — Advanced security

### 4.1 Mutual TLS + JWT binding

* связка server cert + signed token

### 4.2 Certificate pinning

* строгая проверка fingerprint

### 4.3 Automated provisioning

* выдача сертификатов серверам автоматически

---

## Этап 5 — Cleanup

* удалить dev fallback полностью
* удалить deprecated headers из OpenAPI
* зафиксировать финальный контракт

---

# Итог

После выполнения чек-листа должно быть гарантировано:

* mTLS реально работает
* серверы аутентифицируются по сертификату
* public API не сломан
* OpenAPI соответствует реальному поведению
