# JMD para Android

Aplicativo Android nativo em Kotlin para conectar ao JMD executado no computador.

## Recursos

A tela de diagnóstico mostra fabricante, modelo e versão do Android e permite copiar esses dados. O app também pareia com o JMD desktop após aprovação no computador, mantém uma sessão autenticada, lista os comandos remotos disponíveis e permite enviar texto para execução e copiar o resultado.

Para parear, abra **Configurações > Android > Conectar** no JMD desktop e toque em **Ler QR code do JMD** no app. Ao autorizar a câmera, o JMDroid lê o código no próprio aparelho, valida o certificado HTTPS pela impressão digital incluída no QR, aguarda a aprovação explícita no desktop e protege o token com uma chave do Android Keystore. Os dois aparelhos precisam estar na mesma rede local; não exponha a porta do JMD à internet. Também há a opção de colar manualmente o conteúdo JSON.

O JMDroid só lista e executa os comandos que o JMD marca como disponíveis para acesso remoto. Cada execução envia o texto informado no app ao computador; o app não lê mensagens, notificações nem a tela do aparelho.

Captura de tela, operação remota do aparelho, notificações e SMS ainda não estão implementados. Esses recursos devem explicar cada compartilhamento e pedir as permissões/consentimentos próprios do Android quando forem adicionados.

## Gerar APK

Abra esta pasta no Android Studio, aguarde a sincronização do Gradle e selecione **Build > Build APK(s)**. Também é possível compilar pela linha de comando em um ambiente com JDK 17, Android SDK 35 e Gradle 8.11.1:

```sh
gradle assembleDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`. Também há uma URL de download na release pública mais recente após a primeira versão ser publicada: <https://github.com/deggau/jmdDroid/releases/latest/download/jmdroid.apk>. No POCO X3, baixe o arquivo e toque nele para instalar; o Android pode pedir permissão para instalar apps pelo navegador.

O workflow no GitHub Actions também publica o APK debug como artefato de cada execução.
