# OpenAPI stage 3 test report

## Цель

Этап 3 фиксирует минимальный набор проверок, которые подтверждают, что OpenAPI-контракты после этапов 1-2 можно использовать как рабочий контракт для UE Client, Dedicated Server, backend и QA.

## Проверяемые файлы

- `contracts/openapi/public-api.yaml`
- `contracts/openapi/server-api.yaml`
- `contracts/openapi/admin-api.yaml`

## Static contract verification

Команда:

```powershell
powershell -ExecutionPolicy Bypass -File tools/openapi/verify-openapi-stage3.ps1 -RepoRoot .