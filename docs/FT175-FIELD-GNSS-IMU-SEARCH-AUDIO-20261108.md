# FT175 — 8 ноября 2026: интеграция доказательств автомобиля и телефона

## Подтверждено
- Исходный совмещённый кандидат FT174: GitHub cn-openivi/ft-app SHA dbac07b2788f2b714f9b7fcd8e77c68b1dfc0d0a, signed P1 build; FT175 продолжает его код без слияния PR #14.
- Field owner ZIP 14:09:56 / 14:30:01 / 14:43:47, Drive IDs 1Lz2oYwAQdky9FVMILpo89ivKnKUH7lVA / 1AEoZ_18pMwjLkFCu0qSqTRolC79xyeLy / 1w54doSaV-6AXtaTTLISHeNgJS6W7ncut. Третий CSV — накопленный, не суммировать повторы.
- ICCOA присылал GearInfo, GPS, ACCEL, GYRO. В подземном паркинге GPS прекратился раньше GearInfo. OEM gyro через ICCOA сохранялся только как -1/0, отдельные периоды полностью отсутствовал. Не использовать как непрерывный yaw-rate.
- Соседний анализ R28 Field 2026-11-08: штатный Android SensorService на HU содержит ST ASM330LHH с дробным 3-осевым gyro/accel, около 100–111 Гц в тестовом read-снимке. Это **ГУ**, НЕ Samsung; доступ обычного приложения на телефоне к HU SensorService по ICCOA не доказан. Без явного owner-architecture approval не строить HU companion/привилегированный bridge.
- AA объявляет LOCATION, но Samsung подписывается только на GEAR/NIGHT_MODE/DRIVING_STATUS. Никаких неподписанных GPS events в Android Auto не отправлять.
- Миррор Яндекс: Android PlaybackCapture разрешает захват USAGE_MEDIA/GAME/UNKNOWN, не NAVIGATION_GUIDANCE. Сохраняем переносимую подсказку Bluetooth/раздельного звука; не заявлять автоматического перехвата.
- Разделяем WiFi onLost от OEM remote CONTROL 1/23; не утверждать, что причиной был роуминг в паркинге без меток сетевых событий.

## FT175 implementation
1. Циклический экономичный BLE-поиск, пока owner switch ON; нет глобального лимита циклов. Отдельные 20-секундные LOW_POWER окна, 5/10/25-секундные промежутки. OFF останавливает сервис. Auth rejection и PIN требуют владельца; не воспроизводить секреты в фоне.
2. OEM GNSS timestamp gate на FAW наблюдаемом Android wall milliseconds, не принимать 23h-stale как свежий. 7s car-primary UI гистерезис не изменён. PHONE отдаёт координату лишь при GNSS-provider и допустимой точности (если ОС её сообщает).
3. Диагностический CSV — необязательная сырая OEM altitude (без безусловной интерпретации уровня/метров или уклона), плюс смена UI source и ограниченные метаданные PHONE accuracy/provider без координат.
4. WiFi NetworkCallback записывает смену handle и ipv4 state без SSID/BSSID, не меняя OEM протокол.
5. Перенаправление Yandex nav voice через Bluetooth — пользовательская настройка на Samsung, а не утверждение, что MediaProjection умеет захватить запрещённый usage.

## Gate
Unit + exact-SHA CI + полный unsigned APK = P1. Все реальные проверки (переподключения в паркинге, звучания Yandex в машине, AA LOCATION, достоверный уклон/DR) остаются FIELD_UNVERIFIED до owner HU acceptance. Никаких новых требований к GATT/privilege, тесту на движущемся автомобиле, смены планов FT02/ICCOA01 или автоматизаций.
