# FT189 — автомобильный GPS, восстановление потока и перенос FT188

Дата: 16.11.2026. Ветка: `openivi/ft189-car-gps-primary-20261116`.
Исходный базовый commit FT188: `c99cc658cffd6cd5c04d79b7e38aa87156cebca0`.
Кандидат **исходников**: `versionCode=189`, `versionName=3.9.75-xa60-ft189-candidate`.
**Не подписан, APK не создан, полевых PASS FT189 нет.**

## Что перенесено и сделано

1. **GPS:** автомобильный ICCOA/Android LocationManager GPS — основной реальный источник для Android Auto и FT. Автоматического перехода на телефон нет. Существующие `FtPositionTelemetry.updates` → AA `SENSOR_LOCATION` передают только новые события, не повторяют кэш.
2. **GPS пропадание — новая source-only мера:** `IccoaGpsActivationRecovery` наблюдает подтверждённый ICCOA sensor demand и реальные положительные GearInfo скорости; после 10 секунд пропавших GPS пакетов при непрерывном движении повторно активирует существующий OEM `CONTROL category=1 method=11` **один раз на эпизод**. Это не таймер фикса, не буфер координат, не бесконечный keepalive, не новый протокол. Состояние сбрасывается только новым GPS событием или новым соединением. На исходном FAW Carlink `onNavigationInfoReceived(isNavigating)` меняет состояние CarLinkSensorMgr; эффект повторного запроса на реальном ГУ пока **UNKNOWN**.
3. **Диагностика:** добавлено `gps-activation-recovery.txt` в штатный архив поля с анонимными количествами `attempts`, `gpsReceiptsAfterAttempt`, `initial/postFix`, без координат, без утверждения `GNSS loss` или `OEM DR`.
4. **Руль:** из FT188 перенесены `CONTROL12`/CustomKeyEvent `87 NEXT`, `88 PREV`, `DOWN/PRESS` без второго переключения на `UP`, доставка в текущую Android Auto media session при FT home. Полевая приёмка на физическом ГУ остаётся OPEN.
5. **Direct H.264:** перенесён исходный compressed AA AVC→ICCOA RTP без автоматического повторного кодирования после `PAUSE/RESUME`, краткого `AV_STOP/START` и IDR, защищены поколения GOP/очереди, сохранён SmartVideoPacer. `1280x720` в прежнем полевом сеансе подтверждён, `1920x1080` не является PASS.
6. **Касания/масштаб:** сохранены UI-viewport / zoom и ограничения `OK`+необязательная 4-точечная калибровка без жёсткого разрешения AA. Требуются физические проверки.
7. **Скорость:** GearInfo машины = км/ч; детерминированный расчёт только длины пути; нет выдуманного yaw, wheel odometry, точности GNSS, DR lat/lon.
8. **BT HFP/A2DP, яркость, автокачество FPS, wireless debugging:** старые исправления унаследованы вместе с FT188, но самостоятельного нового полевого PASS нет; никакого принудительного объединения BT-карточек или скрытого audio routing.
9. **Достоверность GPS в навигационных приложениях AA:** socket flush подтверждает доставку в транспорт, не использование приложением; PHONE consumption неизвестен.
10. **OEM DR:** согласно окончательному вердикту от 16.11.2026, получение GGA/RMC/$PASCD из IDRProxy в телефонном FT через существующий Carlink/ICCOA **CURRENT-TOPOLOGY NO-GO**. Внутренний DR Toyota не отрицается; отдельный HU bridge — только с новым разрешением владельца. Больше ненужных поездок для прежнего DR теста не назначаем.

## Испытания

- Точный OEM wire для `CONTROL11` / `isNavigating`: метод 11, protobuf field1 bool; пустая инструкция содержит `08 01 1A 00 22 01 6D 32 00`. Отдельный unit-test `IccoaGpsActivationRecoveryTest` покрывает эту байтовую схему, 64s GPS blackout с Gear, восстановление, reconnect, stationary, invalid/reversed/noncontiguous Gear.
- Полный source-only CI: workflow `.github/workflows/ft189-car-gps-primary-source-only.yml`, self-hosted; Kotlin compile + `:app:testUnsignedTransitUnitTest` + XML min1040 тестов, 0 failures/errors/skipped, запрет создания APK.
- CI полный терминальный run: https://github.com/cn-openivi/ft-app/actions/runs/43813214100, exact source SHA `22f6b71204f06f1b69a0d42580d1553976c5506f`: **SUCCESS, Kotlin BUILD SUCCESSFUL, 1040 JUnit / 0 failures / 0 errors / 0 skipped**, `FULL_JUNIT=PASS`, `SIGNED_APK=NOT_BUILT`, `HU_FIELD=NOT_TESTED`. Дальнейшие изменения документации не затрагивают исходники приложения.
- В будущем одна совмещённая поездка только для оставшихся действительно новых P0-полевых проверок: GPS producer при naturally occurring gap, CONTROL12 руль, Direct PAUSE/RESUME. Отдельно фиксировать для каждого PASS/FAIL, ни один не заявлять заранее.

## Ограничения

Нет изменения FTMP master/пяти воркеров, архитектуры, HU permissions, CAN/VHAL, SELinux/AVB или установленного APK. Нет авто-fallback на телефон и синтетических координат. Только исходники, юнит-тесты, диагностика и рабочая ветка. Отчёт: «было → сделал → доказательство → NEXT_EDGE».

## Продолжение FT189 от 16.11.2026 — GPS ingress alignment

- При анализе кодового пути `ICCOA SENSOR Gps → VehicleTelemetry → FtPositionTelemetry → AA SENSOR_LOCATION` устранена конкретная ошибка: декодированный пакет с OEM-позицией (0,0) ранее засчитывался как возобновление GPS для `sensorLiveness`, `IccoaDrSourceGapEvidence` и `IccoaGpsActivationRecovery`, хотя FT/AA уже отвергали Null Island.
- Новый `IccoaGpsWire.hasNavigationPosition` задаёт единую пригодность исходной WGS84 позиции и не вводит спорных age/dedupe-фильтров. (0,37), (55,0), неизменные последовательные координаты, спутники=0 остаются допустимыми. Сырые непригодные сообщения попадают в разреженный счётчик/лог, но не в FT/AA GPS.
- Дополнительные точные wire/regression тесты `IccoaGpsWireTest` и `IccoaGpsActivationRecoveryTest` подтверждают, что (0,0) не закрывает 64-секундную дыру при продолжающемся Gear и только новая действительная позиция закрывает восстановление.
- Источник OEM схемы: <https://drive.google.com/file/d/1euc4KHkJkVBuZFU3MTX_cxPWnUbi-tYZ/view>, оригинальный FAW `CarLinkAdapter$5.onNavigationInfoReceived` с `CONTROL11/isNavigating` и `CarLinkSensorMgr.onStart`. Повторная активация — source-only hint, не подтверждённый фактический рестарт HU `gps` provider.
- <https://github.com/cn-openivi/ft-app/actions/runs/43825733545> — новый полный unsigned CI, exact source SHA `c704c4e73855aa7b3f8a5cbdf8e01f7c510892d4`; результат фиксировать только после терминального readback.
- Отдельная краткая матрица проверки GPS и AA: [FT189-GPS-ICCOA-AA-ACCEPTANCE-20261116.md](../field/FT189-GPS-ICCOA-AA-ACCEPTANCE-20261116.md). В поле пока нет новых PASS, signed APK не создан.
