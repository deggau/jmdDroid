# JMD para Android

Aplicativo Android nativo em Kotlin para conectar ao JMD executado no computador.

## Primeira versão

A tela de diagnóstico mostra fabricante, modelo e versão do Android e permite copiar esses dados. Não pede permissões especiais. A integração com o computador depende de definirmos um protocolo de pareamento e autenticação para o JMD desktop.

Os próximos recursos devem ser adicionados por etapas. O app deve explicar cada compartilhamento e iniciar captura de tela ou controle somente após confirmação explícita no Android. Acesso a notificações e SMS também exige permissões e telas de consentimento próprias; não serão solicitados até que a função correspondente exista.

## Gerar APK

Abra esta pasta no Android Studio, aguarde a sincronização do Gradle e selecione **Build > Build APK(s)**. Também é possível compilar pela linha de comando em um ambiente com JDK 17, Android SDK 35 e Gradle 8.11.1:

```sh
gradle assembleDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`. Também há uma URL de download na release pública mais recente após a primeira versão ser publicada: <https://github.com/deggau/jmdDroid/releases/latest/download/jmdroid.apk>. No POCO X3, baixe o arquivo e toque nele para instalar; o Android pode pedir permissão para instalar apps pelo navegador.

O workflow no GitHub Actions também publica o APK debug como artefato de cada execução.
