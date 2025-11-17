## Capstone 앱 로그 확인 가이드

### 1. Logcat 세팅
- **장치 연결 후** Android Studio `Logcat` 창을 열고 `MainActivity` 태그 또는 문자열 `"[STATE]"`로 필터링합니다.
- 음성 상태 머신/네트워크 흐름은 모두 `Log.d(TAG, "[STATE] ...")` 형식으로 출력되므로 해당 태그만 모으면 됩니다.
- 필요 시 `Regex` 필터로 `(STATE|requestDestination|requestPhotoLocation)` 등을 지정해 세부 흐름만 확인합니다.

### 2. 판단 포인트
- **길안내 명령 후 멈춤**  
  - 로그에서 `STATE → LISTENING_DESTINATION` 이후 `requestDestination(): <사용자 입력>`가 나오면 서버 호출이 이루어진 것입니다.  
  - 응답 성공 시 `STATE → CONFIRMING_DESTINATION 후보=...`가 이어지고, 바로 나타나지 않으면 서버 응답 누락/오류입니다(`requestDestination() → 후보 없음 / 응답 오류`, `requestDestination() 실패` 로그 확인).

- **목적지 확정 후 안내 중단**  
  - `STATE → NAVIGATING 확정 목적지=...` 다음에 `WebSocket connecting → ...` 로그가 찍혀야 합니다.  
  - 이후 `NavigationWebSocketListener` 로그(별도 TAG)와 `[STATE]`가 더 이상 나오지 않으면 WebSocket 메시지 자체가 도착하지 않은 것이므로 서버/네트워크를 의심합니다.

- **내 위치 명령 반복 요청**  
  - `handleGlobalLocation()` 로그로 호출 여부, `requestPhotoLocation() start lat=...` 로그로 요청 여부를 파악합니다.  
  - 응답이 끝나면 `requestPhotoLocation() 완료` 로그가 찍히며, 실패 시 `STATE → MENU (위치 서버 오류)` 등으로 구분됩니다. 같은 요청이 연속 실행되면 `isLocationSession`이 true인 동안 명령을 차단하도록 로직을 검토할 수 있습니다.

- **명령 재진입 실패**  
  - TTS/TTS 종료 이후 `startBeepThenTimedListening` 호출 전에 `speakAndShow(): ...`와 `startListening()` 로그가 반복적으로 나타나는지 확인합니다.  
  - `handleGlobalStop()`나 `stopNavigationInternal()` 이후 `STATE → MENU ...`가 찍혔는데 추가 입력이 없다면 STT 재시작 타이밍을 조사합니다(`startListening()` 로그가 다시 출력되는지 확인).

### 3. 추가 팁
- 로그 타임스탬프를 기준으로 UI의 텍스트 변화(`tvNavigation`)와 비교하면 음성/텍스트 피드백 지연을 쉽게 파악할 수 있습니다.
- QA 시나리오별 체크 리스트  
  1. **길안내 시작 → 목적지 발화**: `STATE → LISTENING_DESTINATION` → `requestDestination` → `STATE → CONFIRMING_DESTINATION`.  
  2. **예/아니오 응답**: `evaluateConfirmation(...)` → `STATE → NAVIGATING 확정 ...` → WebSocket 로그.  
  3. **내 위치 질의**: `handleGlobalLocation()` → `requestPhotoLocation() start ...` → 완료/오류 로그.  
  4. **중지 후 재시작**: `handleGlobalStop()` → `STATE → MENU (global stop)` → 이후 `startListening()`이 다시 찍히는지 확인.

위 흐름과 로그를 대조하면 어느 단계에서 프로세스가 끊어지는지 빠르게 추적할 수 있습니다.

