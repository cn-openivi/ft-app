# FT186-006 — Android Wi-Fi ADB pairing, TLS и SuperCast

Статус: SOURCE_STAGED / BUILD_NOT_RUN / FIELD_NOT_TESTED / NOT_RELEASED.
Решение владельца: Nexus DECISION 180a0e01-7529-4614-818f-3688dfe1bc30.
Основание: Samsung SM-S916B Android 17, FT185, ZIP 20261114-143713 (Google Drive 1uBrpw3VazgLhI0XGsNGtIlvg1XjIPw1_), 14 ошибок `verified TLS debug bridge failed: Transport`; UI показывает одновременно несовместимые сообщения.

## Документированный протокол

AOSP docs: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md
AOSP daemon/mdns.cpp: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/mdns.cpp

1. Первичное сопряжение: `_adb-tls-pairing._tcp`, отдельный временный pairing TLS, SPAKE2+код, PeerInfo `ADB_DEVICE_GUID`.
2. Подключение: отдельный `_adb-tls-connect._tcp`, новый динамический порт, A_STLS → TLS 1.3 → ADB CNXN → shell. Сертификат pairing TLS НЕ обязан совпадать по DER с TLS сертификатом сервера подключения.
3. AOSP service name для secure connect равен `ReadDeviceGuid()`, а сам GUID уже содержит префикс `adb-`; нельзя добавлять префикс ещё раз.
4. Подтверждение доверия: SPAKE2-авторизованный GUID, только NSD endpoint на локальном сетевом интерфейсе и соединение на 127.0.0.1, доверенная клиентская ADB ключевая пара, проверка X509 self-signature/validity, shell UID2000, ContentProvider callback с Binder.getCallingUid()==2000 и свежим токеном, live Binder ping. Лишь после полной цепочки FT сохраняет SHA-256 SPKI ключа connect сервера в app-private файл. Следующие сессии должны совпасть по connect SPKI, даже если X509 serial/DЕR перевыпущен. Этот первый bootstrap аналог локального TOFU с последующей строгой привязкой, а НЕ криптографическое доказательство идентичности connect сервера через pairing сертификат. Локальный mDNS GUID и shell UID сами по себе не заменяют серверную криптографическую аутентификацию на чужой сети.
5. При невозможности любого обязательного этапа отказ. НЕ разрешён downgrade в обычный TCP:5555 после ошибочного доверенного TLS. Не используется root, WRITE_SECURE_SETTINGS, извлечение пароля или ключей в логи.
6. Важная миграция: устаревшее FT185 поле `ft_adb_tls.peer.sha256` было хешем временного pairing X509 и НЕ переносится в качестве доверенного ключа connect. FT186 использует `ft_adb_tls.peer.guid` и `ft_adb_tls.connect.spki.sha256`. Если старый GUID отсутствует, потребуется ОДНО явное повторное сопряжение для миграции. После успешной миграции новый код при обычном reconnect не нужен.

## Точки диагностики и UI

Логи без секретов: `ADB_TLS_STAGE=PAIRING_CODE_ACCEPTED`, `PAIRING_VERIFIED`, `CONNECT_DISCOVERY`, `CONNECT_SOCKET`, `TCP_CONNECTED`, `STLS_ACKNOWLEDGED`, `TLS_VERIFIED`, `ADB_READY`, `SHELL_UID_VERIFIED`, `HELPER_DISPATCHED`, `BINDER_READY`, а для ошибки `ADB_TLS_FAILURE stage=<...> exception=<class> root=<class>` без token, pairing code, IP, сертификатов, ключей и stdout shell.

SuperCastSettings отображает ОДНО актуальное сообщение, не складывает `failed`, `startReason` и `pairing.phase`. Различает неверный/просроченный код, отсутствие connect сервиса, отказ TLS, отказ connect и отсутствие готового Binder. Ошибка пост-pair connect НЕ просит новый PIN автоматически.

## Верификация

SOURCE: проверка независимым GitHub fetch и сравнение с FT186 parent d4efa6a340754aafe9f070c84aa97a98d9b2d311.
UNIT: `AdbServiceIdentityTest` — точный AOSP GUID, чужой GUID, ложный префикс, malformed; `PeerCertificatePinTest` — два самоподписанных X509 с одинаковым RSA ключом и разными DER, отказ для повреждённого X509 и другого SPKI.
BUILD: не запускалась; никаких заявлений о PASS Android Gradle/Unit.
FIELD: не запускалась; Samsung S23+ Android17 проверить первичное сопряжение, повторное соединение после перезапуска, поворот порта, TLS/SPKI mismatch fail-closed, UID2000+Binder ping, отсутствие двойных статусов. Проверять журнал самого устройства, а не выводить pass из наличия кода.
План FTMP, другие FT186 пункты и опубликованный FT185 не изменять.
