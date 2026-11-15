# 數獨 Sudoku

簡潔的 Android 數獨遊戲，支援難度分級，也可以匯入自己的題目。以 Kotlin 撰寫，只使用 Android 內建框架，沒有第三方相依套件，release APK 約 46 KB。

## 功能

- **難度分級**：簡單、中等、困難（約 40／32／26 個提示數字），每一題都保證只有唯一解。
- **匯入題目**：輸入報紙、書上或其他來源的題目來玩，並附上可複製的 AI 提示詞，把題目照片轉成匯入格式。
- **筆記**：在空格中標記候選數字；填入正確數字後，會自動清掉同行、同列、同宮格裡的相同筆記。
- **復原、清除、提示**：提示會填入所選格子的正確數字。
- **錯誤計數與計時**：填錯的數字以紅色顯示。
- **自動存檔**：離開 app 後再回來可以接續上一局。
- **深色模式**：跟隨系統設定。

## 匯入題目

按下右上角的「匯入」，輸入 9 排數字，每排 9 個，空格用 `0` 表示：

```
530070000
600195000
098000060
800060003
400803001
700020006
060000280
000419005
000080079
```

數字以外的字元（空白、逗號、換行）會被忽略，只要總共剛好 81 個數字即可。以下情況會顯示原因，不會開始遊戲：

- 數字不是 81 個
- 同一行、列或九宮格內有重複的數字
- 題目無解，或有不只一組解

### 用 AI 把照片轉成匯入格式

匯入畫面中的「複製 AI 提示詞」會把下面這段提示詞複製到剪貼簿。把它連同題目照片一起傳給 AI（例如 Claude、ChatGPT），再把 AI 的回覆貼回匯入畫面。

```
請辨識這張圖片中的數獨題目，並嚴格依照下列格式輸出：

1. 總共 9 排，每排剛好 9 個數字，由上到下、由左到右對應盤面。
2. 空白的格子用數字 0 表示。
3. 同一排的數字之間不要有空格、逗號或任何其他符號。
4. 只輸出這 9 排數字，不要加上任何說明、標題或程式碼區塊。
5. 輸出前請逐排核對：每排是否剛好 9 個數字、位置是否與圖片一致。看不清楚的格子請填 0，不要猜測。

輸出範例：
530070000
600195000
098000060
800060003
400803001
700020006
060000280
000419005
000080079
```

AI 辨識可能出錯。如果匯入時顯示無解或有多組解，通常是某個數字認錯或漏掉，請對照原題修正。

## 系統需求

- Android 8.0（API 26）以上

## 建置

需要 JDK 17 以上與 Android SDK（platform 34）。用 Android Studio 開啟專案，或在命令列執行：

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Windows 請改用 `gradlew.bat`。

### Release 版本

Release APK 需要簽章。先建立金鑰庫：

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias sudoku -keyalg RSA -keysize 2048 -validity 10000
```

接著在專案根目錄建立 `keystore.properties`：

```properties
storeFile=release.jks
storePassword=你的密碼
keyAlias=sudoku
keyPassword=你的密碼
```

然後建置：

```bash
./gradlew assembleRelease
```

產出的檔案在 `app/build/outputs/apk/release/Sudoku-v<版本>-release.apk`。

`release.jks` 與 `keystore.properties` 已列在 `.gitignore`，請勿提交到版本控制，並自行妥善備份：遺失金鑰庫就無法再以同一個簽章發布更新。

## 專案結構

| 檔案 | 內容 |
| --- | --- |
| `Sudoku.kt` | 題目產生、解題、唯一解檢查、匯入解析 |
| `Game.kt` | 一局遊戲的狀態：填數、筆記、復原、提示、存檔 |
| `BoardView.kt` | 繪製盤面與處理觸控的自訂 View |
| `MainActivity.kt` | 主畫面、難度選擇與匯入對話框 |
| `SudokuTest.kt` | 單元測試 |

原始碼位於 `app/src/main/java/com/example/sudoku/`。
