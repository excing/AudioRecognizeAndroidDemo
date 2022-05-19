# README

采用 TensorFlow Sound Classifier 进行声音分类识别，并在人物说话时，使用 Vosk （alphacephei）进行语音识别。

经测试，该方法在空闲（不说话）时，比全程使用 Vosk 要节省内存和电量等。

可用于语音助手，语音指令等需要长时间进行语音识别的服务。

## Requirements

*   Android Studio 4.1 (installed on a Linux, Mac or Windows machine)
*   An Android device with Android 6.0+

## Build and run

### 1. 下载本 Demo 源码

```
https://github.com/excing/AudioRecognizeAndroidDemo.git
```

### 2. 使用 Android Studio 打开并运行

在 Android Studio 里打开 Demo 源码。打开 Android Studio 选择菜单里的 "Open"，并选择目录里的 Demo 源码。

连接你的 Android 设备（需要 Android 版本 6.0+），并在手机上允许使用 ADB 调试权限。

在 Android Studio 菜单里选择 `Run -> Run app`（`Shift + F10`），并选择刚刚连接的 Android 设备。

### 3. 下载 Vosk Model 并进行语音识别

打开刚刚安装的 Demo，点击`下载模型`，选择一个你熟悉的语言模型下载，**注意，需要是适合手机的微模型（带 `small` 名称的模型）**。

然后返回 Demo，点击`加载模型`，选择刚刚下载的模型，等待加载完成，再点击`开始识别`即可开始语音识别，点击`停止识别`即可停止语音识别。

> 如果已下载模型，可直接加载模型开始识别。

### 4. 播放录音

每次语音结束，都会保存录音，可点击识别结果列表中的`播放`按钮播放录音。

-----

MIT License

Copyright (c) 2022 excing
