# Specyfikacja: moduł `:gauges` — edytor i wyświetlacz własnych zegarów

Status: do realizacji · Platforma docelowa: BYD DiLink 3 (Android 10, API 29) · Autor wymagań: właściciel forka

## 1. Cel

Nowa, osobna aplikacja w tym repo (moduł Gradle `:gauges`, `applicationId = pl.mwalkowiak.bydlakdash.gauges`, namespace `com.byd.dashcast.gauges`),
która:

1. pokazuje pełnoekranowy **dashboard z wskaźnikami** zasilany danymi z auta (API `android.hardware.bydauto.*`);
2. ma **edytor** do budowania własnych układów zegarów z dostępnych sygnałów;
3. zawiera **5 gotowych motywów przykładowych** inspirowanych stylami znanych zegarów samochodowych.

Bydlak Dash (fork DashCast) wyświetla ją na ekranie zegarów (cluster, 1920×720) jak każdą inną aplikację — projekcja i
Fission identyfikują aplikacje po nazwie pakietu i `getLaunchIntentForPackage`, dlatego to osobny APK
z własną aktywnością LAUNCHER. **Nie modyfikuj kodu projekcji, daemona ani `:app`** w tym zadaniu
(wyjątek: `settings.gradle` i ewentualnie wspólna konfiguracja podpisu).

## 2. Środowisko (sesja w chmurze)

- **JDK 17** (nie 21). Na JDK 19+ `\b` w regexach Javy jest tylko-ASCII, przez co testy parsera
  nawigacji w `:app` (`ArabicNavParsingTest`, `NavTextParsersCharacterizationTest`) fałszywie padają;
  na urządzeniu (ICU) działają poprawnie.
- Android SDK: `platforms;android-33`, `build-tools;35.0.0`, `platform-tools`. Jeśli brak — pobierz
  command-line tools z `dl.google.com/android/repository/`, zaakceptuj licencje, ustaw `ANDROID_HOME`
  i `local.properties` (`sdk.dir=...`, plik jest w `.gitignore`).
- Brak `app/keystore/platform.keystore` (gitignored, nigdy go nie commituj). Build podpisuje się
  wtedy kluczem debug — to oczekiwane. API BYD i tak nie działają poza autem.
- Komendy: `./gradlew :gauges:testDebugUnitTest :gauges:assembleDebug` oraz na końcu pełne
  `./gradlew testDebugUnitTest` (stan bazowy na JDK 17: 731/738 zielonych na Windows; 7 testów
  `ProxyShellTest`/`ReportStoreTest`/`RedactionCallSiteTest` wymaga POSIX `sh` — na Linuksie powinny przejść).

## 3. Konfiguracja modułu

- `settings.gradle`: `include ':app', ':gauges'`.
- `gauges/build.gradle`: te same wersje co `:app` — AGP z root `build.gradle`, Kotlin, `compileSdk 33`,
  `minSdk 28`, `targetSdk 29`, Java/Kotlin target 17. Kod nowego modułu **w Kotlinie**.
- `compileOnly files('../app/libs/byd-auto-api-stubs.jar')` — stuby API BYD (na urządzeniu klasy są we frameworku).
- Tożsamość: `applicationId` wyprowadź z `forkApplicationId` w `app/build.gradle` (+ `.gauges`) —
  nie wpisuj go drugi raz na sztywno. Nazwy globalne dla urządzenia bierz z wzorca
  `com.byd.dashcast.proxy.AppIdentity`.
- Podpis: skopiuj logikę warunkowego `bydPlatform` z `app/build.gradle` (keystore istnieje → podpis
  platformowy; brak → debug + sufiks `-debugsigned` w nazwie APK). Bez podpisu platformowego API BYD są odrzucane.
- Zależności: tylko to, co już jest w `:app` (appcompat, recyclerview, constraintlayout, material 1.9,
  junit, robolectric 4.13). Bez nowych bibliotek wykresów — rysujemy sami na `Canvas`.
- **Nie** dopisuj historii wersji w komentarzach `build.gradle` (repo ma tego już 2000 linii).

## 4. Architektura

```
gauges/src/main/java/com/byd/dashcast/gauges/
  data/      VehicleSignal (enum), SignalValue, VehicleDataSource (interfejs),
             BydVehicleDataSource, SimulatedVehicleDataSource, DataSourceProvider
  model/     DashLayout, Widget, WidgetType, WidgetStyle, Theme, LayoutJson (serializacja),
             LayoutValidator
  render/    WidgetView (baza) + DialGaugeView, ArcGaugeView, BarGaugeView, DigitalValueView,
             GearIndicatorView, ShiftLightsView, ClockView, DashboardCanvas (układa widgety)
  editor/    EditorActivity, EditorCanvasView (drag/resize/snap), PropertyPanel, UndoStack
  runtime/   DashboardActivity (LAUNCHER, fullscreen, landscape)
  presets/   PresetRepository (czyta assets/presets/*.json + zapisane przez użytkownika)
gauges/src/main/assets/presets/*.json
```

### 4.1 Dane — `VehicleSignal`

| Sygnał | Jednostka | Źródło (stub) | Listener |
|---|---|---|---|
| SPEED | km/h | `BYDAutoSpeedDevice.getCurrentSpeed()` | `onSpeedChanged(double)` |
| ACCEL_PEDAL | % | `getAccelerateDeepness()` | `onAccelerateDeepnessChanged(int)` |
| BRAKE_PEDAL | % | `getBrakeDeepness()` | `onBrakeDeepnessChanged(int)` |
| BATTERY_SOC | % | `BYDAutoStatisticDevice.getElecPercentageValue()` | `onElecPercentageChanged(double)` |
| EV_RANGE | km | `getElecDrivingRangeValue()` | `onElecDrivingRangeChanged(int)` |
| FUEL_PERCENT | % | `getFuelPercentageValue()` | `onFuelPercentageChanged(int)` |
| FUEL_RANGE | km | `getFuelDrivingRangeValue()` | `onFuelDrivingRangeChanged(int)` |
| ODOMETER | km | `getTotalMileageValue()` | `onTotalMileageValueChanged(int)` |
| CONSUMPTION_ELEC | kWh/100 km | `getLastElecConPHMValue()` | `onLastElecConPHMChanged(double)` |
| DRIVING_TIME | h | `getDrivingTimeValue()` | `onDrivingTimeChanged(double)` |
| GEAR | enum P/R/N/D/S/M | `BYDAutoGearboxDevice.getGearboxAutoModeType()` | `onGearboxAutoModeTypeChanged(int)` |
| PARK_BRAKE | bool | `getParkBrakeSwitch()` | `onParkBrakeSwitchChanged(int)` |
| CHARGING_POWER | kW | `BYDAutoChargingDevice.getChargingPower()` | `onChargingPowerChanged(double)` |
| CHARGER_STATE | enum | `getChargerState()` / `getChargingGunState()` | `onChargerStateChanged(int)` |
| DRIVE_MODE | enum Eco/Sport | `BYDAutoEnergyDevice.getOperationMode()` | `onOperationModeChanged(int)` |
| ENERGY_MODE | enum EV/HEV | `getEnergyMode()` | `onEnergyModeChanged(int)` |
| ENGINE_RPM | rpm | `BYDAutoEngineDevice.getEngineSpeed()` (tylko DM-i) | `onEngineSpeedChanged(int)` |
| TIME | — | zegar systemowy | tick co 1 s |

Wymagania:
- `BydVehicleDataSource`: `getInstance(context)` każdego urządzenia w `try/catch(Throwable)`;
  urządzenie niedostępne → jego sygnały mają status `UNAVAILABLE` (nie crash). Najpierw odczyt
  getterem (wartość startowa), potem listener. Wartości walidowane z zakresami `*_MIN/*_MAX` ze stubów
  (poza zakresem → `INVALID`).
- `SignalValue(value: Double, status: OK|STALE|UNAVAILABLE|INVALID, timestampMs)`; `STALE`, gdy brak
  aktualizacji > 2 s dla SPEED (dla pozostałych > 30 s).
- Dostarczanie do UI: wątek główny, łączenie zmian i odświeżanie maks. **30 fps** (Choreographer),
  rysowanie tylko gdy wartość się zmieniła.
- `SimulatedVehicleDataSource`: deterministyczna symulacja jazdy (przyspieszanie, hamowanie, zmiana
  biegów, spadek SOC, ładowanie) — używana w edytorze, w testach i gdy włączony przełącznik
  „Symulacja” w ustawieniach.
- Mapowanie stałych enumów (np. `GEARBOX_AUTO_MODE_D`) — przez wartości stałych ze stubów, nie wpisane na sztywno liczby.
- Uprawnienia w manifeście: istniejące w `:app` `BYDAUTO_SPEED_*`, `BYDAUTO_ENERGY_*`,
  `BYDAUTO_GEARBOX_*`, `BYDAUTO_ENGINE_*`, `BYDAUTO_INSTRUMENT_*` + **dodaj**
  `BYDAUTO_STATISTIC_COMMON/GET` i `BYDAUTO_CHARGING_COMMON/GET`. Nazwy dwóch ostatnich nie są
  potwierdzone — zostaw komentarz, że trzeba je sprawdzić w aucie: `adb shell pm list permissions | grep BYDAUTO`.

### 4.2 Model układu — JSON

```json
{
  "schema": 1,
  "name": "Sport Tach",
  "canvas": { "width": 1920, "height": 720 },
  "theme": { "background": "#0B0B0D", "foreground": "#F2F2F2", "accent": "#FFD100",
             "warning": "#E10600", "font": "condensed" },
  "widgets": [
    { "id": "w1", "type": "DIAL", "signal": "SPEED", "x": 660, "y": 40, "w": 600, "h": 640,
      "style": { "min": 0, "max": 240, "majorTicks": 12, "minorTicks": 4, "redlineFrom": 200,
                 "startAngle": 225, "sweep": 270, "face": "#FFD100", "needle": "#E10600",
                 "showDigital": true, "label": "km/h" } }
  ]
}
```

- `WidgetType`: `DIAL` (okrągły ze wskazówką), `ARC` (łuk wypełniany), `BAR` (poziomy/pionowy),
  `DIGITAL` (duża liczba + jednostka), `GEAR` (P R N D z podświetleniem), `SHIFT_LIGHTS`
  (pasek LED: zielone → żółte → czerwone; dla EV sterowany mocą/przyspieszeniem),
  `CLOCK`, `LABEL` (tekst statyczny).
- `LayoutJson`: ręczna serializacja `org.json` (jak `fission/LayoutPreset.kt`), nieznane pola
  ignorowane, `schema` wersjonowane, wartości przycinane do sensownych zakresów.
- `LayoutValidator` — **reguła bezpieczeństwa**: układ musi zawierać co najmniej jeden widoczny
  widget z sygnałem `SPEED` o rozmiarze ≥ 160×80 px; edytor nie pozwala zapisać układu, który tego nie
  spełnia (komunikat zamiast cichej blokady). Widgety nie mogą wychodzić poza płótno.

### 4.3 Renderowanie

- Każdy widget to `View` rysujący na `Canvas`; **zero alokacji w `onDraw`** (Paint/Path/RectF
  tworzone raz, jak w `ClusterInputForwarder`).
- Płynna wskazówka: interpolacja do wartości docelowej (krytyczne tłumienie, ~150 ms), bez
  przestrzelenia.
- Status `STALE/UNAVAILABLE` → wskaźnik wyszarzony i „--”, nigdy stara liczba udająca aktualną.
- `DashboardActivity`: pełny ekran (immersive), tylko landscape, `keepScreenOn`, skaluje układ
  1920×720 do faktycznego rozmiaru wyświetlacza z zachowaniem proporcji (letterbox). Tryb nocny:
  przyciemnienie wg ustawienia.

### 4.4 Edytor

- `EditorActivity` na ekranie głównym (15,6"): po lewej podgląd płótna 1920×720 w skali, po prawej
  panel właściwości, na górze pasek: Nowy · Otwórz preset · Zapisz · Eksport · Import · Cofnij/Ponów ·
  Podgląd (symulacja jazdy) · Pokaż na zegarach.
- Dodawanie widgetu: lista typów → wybór sygnału (filtrowana do sensownych dla typu).
- Przeciąganie i zmiana rozmiaru uchwytami w rogach, przyciąganie do siatki 8 px i do krawędzi
  innych widgetów, zaznaczenie, usuwanie, duplikowanie, kolejność warstw.
- Panel właściwości zależny od typu (zakres, podziałka, czerwone pole, kąty, kolory z palety motywu,
  etykieta, jednostka km/h ↔ mph).
- Zapis: wewnętrzny katalog aplikacji (`filesDir/layouts/*.json`); eksport/import przez
  `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` (bez uprawnień do pamięci).
- Aktywny układ wybierany w edytorze; `DashboardActivity` wczytuje go przy starcie i po zmianie.
- „Pokaż na zegarach” = tylko instrukcja/skrót: projekcję robi Bydlak Dash (wybór `pl.mwalkowiak.bydlakdash.gauges`
  na liście aplikacji). Nie implementuj własnej projekcji.

### 4.5 Gotowe motywy (assets/presets)

Odtwarzamy **styl**, nie znaki towarowe: **bez logo, nazw marek i wiernych kopii tarcz** konkretnych
producentów (repo jest publiczne). Nazwy neutralne, opis inspiracji tylko w tym dokumencie.

| Plik | Nazwa | Inspiracja (opisowo) | Zawartość |
|---|---|---|---|
| `sport_tach.json` | Sport Tach | włoskie supersamochody: duży centralny obrotomierz z żółtą tarczą | centralny DIAL (SPEED lub ENGINE_RPM), czerwona wskazówka, po bokach DIGITAL SOC i zasięg, GEAR |
| `classic_five.json` | Classic Five | klasyczne niemieckie sportowe auta: pięć okrągłych zegarów w rzędzie | 5× DIAL: SOC, moc/pedał, SPEED (środek, największy), zasięg, zużycie |
| `minimal_ev.json` | Minimal EV | minimalistyczne kokpity EV | wielka DIGITAL prędkość, BAR moc/rekuperacja, SOC, GEAR, CLOCK |
| `racing.json` | Racing | kierownica wyścigowa | SHIFT_LIGHTS u góry, olbrzymi GEAR, DIGITAL SPEED, BAR pedałów |
| `retro_analog.json` | Retro Analog | lata 60./70.: chromowane pierścienie, kremowe tarcze | 3× DIAL z obwódkami, czcionka szeryfowa, ciepłe kolory |

Każdy preset musi przejść `LayoutValidator` (test jednostkowy na wszystkich plikach z assets).

## 5. Testy (Robolectric + JUnit, w `gauges/src/test`)

Testuj **zachowanie**, nie tekst źródeł (nie powielaj wzorca `*WiringTest` z `:app`, który grepuje kod).

- `LayoutJson`: round-trip, nieznane pola, brakujące pola → wartości domyślne, wartości poza zakresem przycinane.
- `LayoutValidator`: brak SPEED → błąd; widget poza płótnem → błąd; wszystkie presety z assets → OK.
- Mapowanie wartość → kąt wskazówki (min, max, poza zakresem, odwrócony łuk).
- `SimulatedVehicleDataSource`: deterministyczność, zakresy wartości.
- Logika `STALE` (sterowany zegar w teście).
- Edytor: przyciąganie do siatki i krawędzi, cofnij/ponów (logika w czystych klasach, bez UI).
- `BydVehicleDataSource` z brakującą klasą/urządzeniem → `UNAVAILABLE`, bez wyjątku.

## 6. Poza zakresem (kolejne etapy)

- Widget strzałki nawigacji (dane z `MapNotificationListenerService` w `:app` — wymaga broadcastu
  chronionego uprawnieniem `signature`). Zostaw tylko interfejs `NavDataSource` i typ `NAV_ARROW`
  oznaczony jako niedostępny.
- Wybór źródła strzałek HUD, nowe parsery nawigacji, auto-start nawigacji na zegarach.
- Preset Fission „Nawigacja + wskaźniki” w `:app`.

## 7. Definicja ukończenia

- `./gradlew :gauges:testDebugUnitTest :gauges:assembleDebug` zielone; pełne `testDebugUnitTest`
  bez nowych błędów w `:app`.
- APK `gauges` uruchamia się na emulatorze API 29 w trybie symulacji: dashboard, edytor, 5 presetów.
- Krótki `gauges/README.md`: jak zbudować, zainstalować (`adb install -r`), wybrać w DashCast,
  jakie sygnały zweryfikować w aucie (lista z 4.1) i jak sprawdzić nazwy uprawnień.
- Zmiany na gałęzi `feature/gauges-editor`, opisany PR do `main` forka (nie do `Kiroha/byd-dashcast`).
