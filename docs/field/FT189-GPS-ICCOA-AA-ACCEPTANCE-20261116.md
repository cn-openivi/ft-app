# FT189 — контрольная проверка GPS автомобиля → FT → Android Auto

**Только после отдельного owner-approved подписанного тестового APK.**
Не проверять OEM DR через повторные поездки: по существующей топологии IDRProxy→ICCOA→FT готовый DR недоступен.
Тест не делает HU writes, не требует ADB/root, не меняет VHAL/CAN/DCM.
Процесс управляется владельцем на безопасной дороге, никаких действий с экраном в движении.

## Один полевой прогон — четыре исхода

1. Подключить ICCOA, открыть AA с приложением навигации; проверить, что режим GPS выбран **CAR**, без PHONE auto fallback. Экспортировать штатный FT ZIP после завершения.
2. **При обычном движении** сначала записать наличие настоящего `ICCOA SENSOR GPS received` и `AA LOCATION socket flushed`. Доступность GPS в самих Картах на телефоне не следует из socket flush — подтверждать отдельно.
3. Если естественно возник GPS blackout (нет новых годных lat/lon, а GearInfo продолжает поступать), ожидать **не более одной** `ICCOA_GPS_CONTROL11_REARM kind=GPS_RECEIPT_STOPPED attempt=1/1`, а затем `OEM sensor activation request: active=true` с уже известным метод11/поле1. Если вместо этого приходят (0,0), такие пакеты теперь отмечены `OEM GPS decoded but NOT usable for navigation` и не сбрасывают recovery. Никакой специальной поездки для создания пропадания/подземного паркинга не требуется.
4. При естественном возобновлении годных реальных координат должны увеличиваться `gpsReceiptsAfterAttempt`, `SENSOR GPS received` и, **если телефон запросил LOCATION**, AA `socketWritesFlushed`. Новый эпизод при следующем самостоятельном обрыве разрешает ровно одну новую попытку.

## Разбор по готовым счётчикам

Проверить в полевом ZIP:
- `gps-activation-recovery.txt`: `attempts`, `gpsReceiptsAfterAttempt`, `lastReason`, `lastSilenceMs`, `frozenAtDisconnect`. Пакет (0,0) не считается восстановлением.
- `dr-source-gaps.txt`: initial/wait и реальные OEM GPS **receipt** gap; даже 63 секунды получения без GPS не доказывают физическую потерю спутников.
- `aa-car-gps-evidence.txt`: `locationOffered`, `sensorChannelOpened`, `locationRequests`, `eventsSubmitted`, `socketWritesFlushed`, `diagnosis`.
- `dr-path-evidence.txt`: отдельно подтверждает, что FT **не** опубликовал выдуманный DR.

**Классификация без новых круговых тестов:**
- `locationRequests=0`: телефонный AA не подписался; работа над реальной доставкой в AA начинается с sensor negotiation, не с HU DR.
- `locationRequests>0`, GPS ingress=0: первичный источник/Carlink/HU; CONTROL11 — разовый activation hint, не подтверждённое исправление до field PASS.
- GPS ingress>0, `eventsSubmitted=0`: проблема FT authoritative feed/диспетчеризации.
- `eventsSubmitted>0`, `socketWritesFlushed=0`: AA transport/socket; это не проблема получения координат автомобилем.
- `socketWritesFlushed>0`: FT записал real OEM WGS84 в AA transport. Фактическое принятие и использование в навигации приложением требует проверки на экране; не выдавать socket flush за app-consumption.

## Что считать закрытым

Для **source**: терминальный exact SHA unsigned compile+JUnit PASS.
Для **field**: приложение принимает настоящий OEM GPS во время обычного движения; при реальном blackout CONTROL11 отправляется не более раза, пустой OEM (0,0) не закрывает его, возвращённые координаты вновь уходят в AA при подписке. Если blackout не случился, считать recovery **NOT_TESTED**, а не PASS.
Отдельно не объявлять PASS для OEM DR/GNSS HAL, руль 87/88, Direct, 1080p, BT или касаний без их собственных результатов.
