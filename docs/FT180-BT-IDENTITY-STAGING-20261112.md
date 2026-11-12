# FT180 (SOURCE ONLY) — OEM ICCOA/Bluetooth HFP identity association

Base: FT179 owner-signed field source `b399c20a89a8ed513394bb7febd4f53ca54d89ad`.
Owner approved 2026-11-12; FT180 branch only. **DO NOT RUN GRADLE / CI / APK / SIGN / FIELD** before separate owner command.

## Verified OEM contract
- Exact XA60 factory Carlink.apk SHA256: `6292f74524a5f7eecb84fe80d7b4c63acd81835912f03cf085ee78ae44c076d7`
- `ucar.proto`: ControlIndex.bluetooth_mac = 22; BluetoothMacInfo op_type = 1 (ADD0, DELETE1); bluetooth_mac = 2 (string)
- OEM UCarAdapter stores phoneId->supplied hashed BT MAC in `car_bluetooth_sha265_config`; it looks up SHA256(exact raw HU Classic phone MAC string). Not Android Bluetooth bond key and not synthetic ICCOA Wi-Fi MAC.
- ADD body = protobuf field2 length64 SHA256 lower-hex: `12 40` + 64 ASCII bytes (66 total).
- On FT179, no CONTROL22 sender. Android 17 public API cannot be assumed to reveal phone's Classic MAC.

## Implementation scope
- `IccoaBluetoothMacInfo`: protocol codec + SHA256 exact HU-rendered MAC, fail closed on malformed/02:00 masked.
- `IccoaBluetoothIdentityLinkStore`: holds only SHA256 and off-by-default owner consent, outside FT ordinary Prefs export.
- Settings > Car > ICCOA: manually enter phone MAC as shown by HU, explicit confirmation, save hash, separately enable experimental sending; can disable/forget without OEM DELETE.
- `IccoaSession`: callback fires only once after signed AuthConfirm and authenticated CONTROL lane BOTH present; Quick/Normal authentication logic unchanged.
- `IccoaService`: send category1/method22 ADD once per authenticated session if opt-in and valid hash; log queued vs failed, NEVER claim OEM ACK.
- No automatic Bluetooth reconnect/HFP workarounds, no system pairing edits, no phone/car key substitutions.

## Pending gates
1. OEM schema and original code: P1 source verified.
2. Source staging and deterministic wire regression: added but tests UNEXECUTED (no compilation by owner order).
3. Gradle build, full release/unsigned APK CI: NOT STARTED.
4. Real parked-car A/B: NOT STARTED. HFP drops BEFORE ICCOA authentication in recent logs; cannot claim this post-auth message prevents the initial drop. Verify OEM mapping, next-session HFP, actual call audio, and HU single-row display separately.
5. Exact Samsung Classic BT MAC as presented by the HU is not yet available; do not guess it.

Research: https://drive.google.com/file/d/18_WhW2FGjOfEdI4Y-7anq4PQaw4gQb9S/view
Nexus owner decision: a6b906c3-612c-4c9c-9b03-3cd52f7eb087


## [FT180_HEADSWAP_SCOPE_20261112_V1] Дополнительный объём, утверждённый владельцем

**Nexus owner DECISION**: `c3476457-f816-49b9-a861-46571113ea48`, key `FT180-FTMP-HEADSWAP-RESEARCH-INTEGRATION-20261112.v1`. Добавлено **к**, а не **вместо** существующей задачи ICCOA Control22 / Bluetooth HFP. Источник исследования: [глубокий аудит Headswap 4.2.0 ↔ FTMP](https://docs.google.com/document/d/1xoa1Ctq_giHyqRIPFQ97ZB2rjQKB7IPe9h1a85Mu_U0/edit).

**Состояние после записи:** `APPROVED_SCOPE / NOT_IMPLEMENTED`. Этот коммит меняет **только документ**. Прежний режим `SOURCE_ONLY` сохраняется: **не запускать Gradle, unit tests, CI, APK, подписание, установку и полевые испытания** без отдельной команды. Текущий FT179 и его подпись не изменять. Не менять согласованную архитектуру FT, не переносить исходники GPL-3 из Headswap.

### FT180-A — ICCOA видеогеометрия и калибровка (приоритет P0, исходники P1)

- Исправить несоответствие между `IccoaMediaTransport.input` (раздельное X/Y масштабирование) и `CarDisplay.toContent` (`preserveAspect`, единый коэффициент и поля). За основу принимать **реальную согласованную геометрию** и координатную область UIBC, не автоматический перевод в 2422×1272 на любой машине.
- Проверять в fixtures `content=2422×1272, stream=1920×1080, preserveAspect=true`, центр/края/3×3 точки, portrait/landscape, AA crop и pillar/letterbox. Исследованная ошибка Y — следствие формул при native-координатах, не полевое доказательство; наблюдаемый сдвиг X — отдельный симптом.
- Подключить `MirrorTouchCalibration.map()` к фактической ветке mirroring, сохраняя профили ориентации/ГУ и исключив двойное применение `CarTouchCalibration.correct()`.
- DoD: детерминированные тесты преобразований и интеграционного маршрута, нет двойной калибровки и магических поправок; on-HU 3×3 проверка только с разрешения владельца.

### FT180-B — жизненный цикл касаний (P0)

- Поддержать безопасную последовательность `DOWN / MOVE / UP / CANCEL`, включая UIBC EOF, переподключение и смену маршрута. `CANCEL=3` сейчас принимается парсером, но AA/mirror его отбрасывают, а нативный `CarDisplay` трактует как MOVE.
- Фиксировать viewport/размеры AA и mirroring от начала до завершения жеста. Не терять финальный `MOVE` при `UP` и отложенный `MOVE` перед `POINTER_DOWN`; гарантировать сброс активных пальцев на отмене.
- Сохранить FT-owned UI hit testing и корректность экранной клавиатуры/long press/drag.
- DoD: маршрутный тест `DOWN→resize/rotate→MOVE→UP`, `DOWN→CANCEL(no EOF)→new DOWN`, `MOVE pending→POINTER_DOWN`, reconnect/EOF; никакого ghost tap или stuck pointer. Не подменять `CANCEL` неподтверждённым автомобильным действием.

### FT180-C — диагностическая полнота и причины (P1)

- В `DiagLog` учитывать возврат `pending.offer=false`: счётчик потерянных записей, флаг неполного отчёта, bounded nonblocking хранение и проверяемое завершение записи. Не терять обратную совместимость ZIP и защиту координат/PIN/ключей.
- Разделять `AA SENSOR_CHANNEL_NOT_OPENED`, `LOCATION_NOT_ADVERTISED`, `LOCATION_ADVERTISED_BUT_NOT_REQUESTED`, `OEM_GPS_UNAVAILABLE`; после `Control11` — `ENQUEUED`, `SOCKET_WRITTEN`, `OEM_REACTION_UNKNOWN`.
- Использовать существующие RTP/FrameGate метрики; офлайн rule/facts-first диагностика с `UNKNOWN` при неполных логах и positive/negative golden fixtures.
- DoD: тест переполнения/дописывания, разделение стадий, строгое отсутствие выдуманного подтверждения HU; никаких автоматических действий на машине.

### FT180-D — H.264/IDR восстановление (P1)

- Сохранить текущий composite virtual display: Android Auto + собственный FT UI. **Не** внедрять прямой AA passthrough и `IdrSplice` Headswap.
- Исследовать и покрыть тестом input-buffer starvation в `AaVideoDecoder`: 2 ms неблокирующее ожидание сохранить; учесть пропущенный ссылочный P-frame, запрос нового изображения и восстановление на валидном IDR **без keyframe flood**, с сохранением AA control/ACK.
- При владельческом field-gate проверить смену штатная камера → FT video, PAUSE/RESUME, gap/age/IDR и фактическое изображение; FPS, RTP backlog, keyframe и write latency уже частично логируются, не дублировать.

### FT180-E — мультитач, только условно (P1 / OEM field gate)

- Входной ICCOA UIBC парсер уже поддерживает `POINTER_DOWN=5`, `POINTER_UP=6`, `x2/y2`, но маршруты AA/mirror их отбрасывают; `AaMessages.inputTouch` отправляет один pointer. Для AA предусмотреть корректные повторные pointer entries, `action_index` и стабильные ID в пределах жеста.
- **Сначала** доказать, что штатный China XA60 ICCOA receiver объявляет/присылает `MultiTouch`. FT сейчас рекламирует только `SingleTouch`; **не объявлять `MultiTouch` наугад**. Только после P0 — end-to-end `0→5→2→6→1`, сохраняющееся закрытие жеста.
- Mirroring использует одиночные Accessibility strokes и потребует **отдельной** проверки возможностей ОС. Не заявлять, что pinch на Toyota работает, без подтверждения.

### Исключения и release gates

Не добавлять в обязательную FT180-реализацию прямой сторонний `CarAppHost` Organic Maps (штатный release валидирует доверенного host), speculative `MeterInfoService`/ICCOA cluster arrows, virtual desktop/DeX, новый GPS source, изменение архитектуры, новые ветки автоматизаций или новые планы. Рабочий канал AA NAV → `Control11` не доказывает стрелки на приборке. Все source-баги P1, автомобильные способности P0 UNKNOWN.

FTMP имеет **отдельную** каноническую привязку этих же требований к существующим 001–120 задачам W1/L01, W2/L02, W3/L03, W4/L04. Перенос совместимых исправлений должен соблюдать file ownership и интеграционный gate, не переносить незавершённый FT180 напрямую в FTMP. В этой ветке `ft180-staging-btmac-20261112` не запускать CI, пока владелец явно не утвердит следующий этап.
