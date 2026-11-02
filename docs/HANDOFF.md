# 開發交接(VR網頁播放器)

換新的對話時,先讀這份文件,再看 [README.md](../README.md)(操作說明)和 [VERSIONS.md](VERSIONS.md)(版本紀錄)。

## 1. 使用者與合作方式
- 使用者用**繁體中文**溝通,回覆也用繁體中文
- 習慣:**先討論,確認後才實作**。使用者說「照這樣做 / 好,改」才動手
- 測試手機:小米 9(MIUI、Android 11),用 Cardboard 類的 VR 盒子
- 目標族群:長輩、行動不便者(坐著用)。很在意**頭暈**,操作要簡單、移動要緩慢
- 不想付費:Google 地圖只在免費額度內使用

## 2. 版本與發佈流程
- **1.0 自用完整版**:標籤 `v1.0`,已定版,不再修改
- **2.0 研究版**:持續開發,版本號 `2.0.N`(N = CI 編號)。目前最新 **2.0.113**
- 流程:在分支 `claude/vr-app-generation-vxzej7` 開發 → push → 開 PR 合併到 `main`
  → GitHub Actions 編譯、跑啟動測試和 `tools/check_pages.py` → 發佈到 Releases 的 `latest`(VrWebPlayer.apk + version.json)
  → 手機上的 App 自動檢查到新版,詢問是否更新
- **Google 金鑰絕對不能放進 GitHub**:只存在手機的設定(prefs `mapsKey`)
- `chat/` 資料夾是另一個 App(整人聊天),不是這個專案,不要動
- `meal/` 資料夾是另一個 App(員工餐),交接見 [meal-handoff.md](meal-handoff.md)

## 3. 架構與檔案
網頁 WebView → 私有虛擬螢幕(VirtualDisplay + Presentation,正方形 L×L)→ OpenGL 材質 → 2D 畫一份 / VR 左右各一份

| 檔案 | 內容 |
|---|---|
| `app/src/main/java/com/cck/vrweb/MainActivity.kt` | 主畫面:首頁、設定、網址列、VR 模式、頭部追蹤、面板邏輯、拍盒子偵測、播完選單、VR 網頁紅點、YouTube 交給 YouTube App |
| `WebPresentation.kt` | 虛擬螢幕上的 WebView:載入網頁、影片控制(JS)、模擬點擊、全螢幕、網頁記憶體不足重建、Google 地圖錯誤代碼 |
| `VrRenderer.kt` | OpenGL 繪圖:2D、左右3D、VR180、上下VR、環顧、2D曲面(圓柱 / 球形、邊緣拉伸)、面板與紅點疊加 |
| `VrMenu.kt` | VR 面板的按鈕、設定頁、模式選單、播完選單(重播 / 回到網頁 / 離開 VR)的繪圖與點選判斷 |
| `LocalVideoServer.kt` | 「我的影片」用的本機小伺服器(127.0.0.1,支援拖進度條) |
| `Updater.kt` | 自動更新(下載 APK,用系統安裝畫面安裝) |
| `app/src/main/assets/flight.html` | 空中遊覽:Google 3D 地圖飛行、地圖選點、降落街景、散步、VR 轉向邏輯 |
| `assets/player.html`、`start.html` | 我的影片播放頁、網頁瀏覽起始頁 |
| `tools/check_pages.py` | CI 檢查內建網頁的 JS 語法 |

## 4. 目前主要行為與參數(調整時改這些地方)
**VR 轉向**(flight.html 開頭的常數,空中飛行與街景共用)
- `TURN_DEAD = 40`:頭轉 40 度內畫面不動
- `TURN_HOLD = 2000`:超過門檻要停留 2 秒才開始轉
- `TURN_MAX = 6`:每秒最多轉 6 度;頭往回轉 4 度以上就停,並以當下方向為中心
- `TURN_RANGE = 60`:以正前方為準左右最多 60 度
- `AUTO_TURN_SEC = 5`:拍兩下轉身 90 度的時間

**街景散步**(flight.html)
- 自動散步預設 `walkSec = 6` 秒一步(2~10 秒可調)
- 每步:查位置與日期 → 對準方向(0.5 秒)→ 依距離放大(1.12~2.2 倍、0.7~2 秒)後換成預載的下一張;超過 35 公尺或不同日期改淡入
- 兩層街景(`#sv` / `#sv2`):後面那層預先載入下一張

**空中遊覽**(flight.html)
- 飛低下限 `MIN_ABOVE = 40` 公尺(地標頂端上方);面板飛高 / 飛低每次 1.3 倍(MainActivity `bumpFlight`)
- 到新位置先等 3D 地圖成形(`gmp-steadychange`,最多 10 秒)

**拍盒子兩下**(MainActivity `detectKnock`)
- 加速度尖峰兩次、間隔 0.12~0.6 秒;靈敏度 `KNOCK_LEVELS = 2.5 / 4 / 6`(高 / 中 / 低)
- 點選用「第一下之前」的紅點位置(拍的時候手機會晃)

**VR 網頁紅點**(MainActivity)
- `WEB_EDGE = 0.05`:紅點貼到最邊緣、停 0.35 秒才捲動 / 平移;左右邊緣拍兩下 = 縮小 / 放大
- 紅點中心 = 正前方;從面板按「網頁」後要抬頭才會再叫出面板

## 5. 已經決定不做 / 已移除的事(和原因)
- **3D 照片(立體圖)**:效果不明顯,已刪除
- **8K 畫質**:小米 9 會當機,已移除
- **App 內的 YouTube VR 播放器**:WebView 裡 YouTube 的 VR 影片轉動範圍被限制、畫面跳動,查不出原因 → 改成 VR 影片按全螢幕時交給 **YouTube App** 播放(App 有 Cardboard 眼鏡模式),播完自動回來
- **把街景的負片顏色改回正常**:那是 Google 對沒綁帳單的限制,改顏色等於繞過付費機制,違反使用條款,不做
- **預先下載 3D 地圖 / 街景到手機**:Google 使用條款禁止預先下載與儲存,不做;只使用瀏覽器一般的暫存
- **凝視地面前進(黃色箭頭)**:使用者要求刪除
- **擷取 YouTube App 畫面放進自己的 VR**:延遲大、容易暈,而且 YouTube App 本身就有 VR 模式,不做

## 6. Google 地圖金鑰與費用
- 使用者的專案:Maps Platform Demo Project(已綁定帳單)
- 需要的 API:Maps JavaScript API、Map Tiles API(Elevation API 可選)
- 建議的每天上限(Maps JavaScript API 的配額頁):Map loads per day = 300、3D Map loads per day = 30
  (下次對話可先確認使用者是否已設定)
- 預算提醒:當時清單中沒有地圖服務可選,使用者決定暫不設定
- 上架給大眾時的費用問題還沒決定(選項:使用者自備金鑰、付費解鎖、換免費資料來源),先給長輩試用看反應

## 7. 待辦與想法
- 長輩試戴:空中遊覽、街景散步、YouTube 360 旅遊影片,收集反應
- 語音指令(固定指令:前進、停、迴轉、起飛、降落):要麥克風權限、耗電;建議「拍盒子後開始聽一句」,尚未做
- 2D曲面的「鏡面補邊」:使用者說再看看,尚未決定
- 首頁「編輯旅程」(自己拍的旅行影片 + 註解):只留位置,尚未做
- 上架 Google Play 前必須處理的事:見 [VERSIONS.md](VERSIONS.md) 最後一段

## 8. 開發時的檢查方式
- 本機沒有完整 Android 編譯環境:用 `kotlinc` + android-all.jar + 少量 androidx.webkit 替身檔檢查 Kotlin 能不能編譯
- 內建網頁:`python3 tools/check_pages.py`(CI 也會跑)
- 著色器(GLSL):可以用無頭 Chromium 的 WebGL(SwiftShader)編譯、畫測試圖檢查
- 真正的效果一定要請使用者在手機上試;改動前後都用繁體中文說明要怎麼測
