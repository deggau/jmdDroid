# JMD para Android

Aplicativo Android nativo em Kotlin para conectar ao JMD executado no computador.

## Recursos

A tela de diagnóstico mostra fabricante, modelo e versão do Android e permite copiar esses dados. O app também pareia com o JMD desktop após aprovação no computador, mantém uma sessão autenticada, lista os comandos remotos disponíveis e permite enviar texto para execução e copiar o resultado.

Para parear, toque em **Conectar ao JMD** e escolha **Buscar computadores na rede**. O JMDroid envia um broadcast UDP com nonce aleatório e valida a assinatura RSA-PSS da resposta antes de listar nome, endereço, versão e identidade de cada computador. Ao escolher um resultado, o app confirma novamente a identidade do JMD por desafio assinado, fixa o certificado HTTPS recebido e cria no Android Keystore uma chave RSA privada não exportável. O app assina o pedido de conexão e aguarda a aprovação explícita no desktop; a resposta de aprovação também é validada por assinatura antes de guardar o token cifrado.

Se a rede bloquear broadcast, use **Ler QR code** ou **Colar QR code** como alternativa. O QR fornece o endereço e, em HTTPS, a impressão digital do certificado; ele inicia o mesmo pareamento por identidade, não o fluxo legado por segredo. HTTP exige confirmação porque o pedido e o token trafegam sem criptografia; use apenas em rede local confiável e não exponha a porta do JMD à internet. Em reconexões, o app redescobre o computador, verifica que a identidade RSA salva não mudou e solicita a renovação assinada do token. Se o broadcast estiver bloqueado, verifica a identidade no endereço salvo antes de renovar.

O JMDroid pode manter pareamentos com vários computadores. A tela inicial mostra o estado online/offline de cada um, permite alternar o computador ativo e remover pareamentos com um toque longo. Comandos, tarefas e compartilhamento usam o computador ativo.

Durante o compartilhamento da tela do celular, o desktop pode enviar cliques e rolagens. Para isso, o Android exige que a pessoa habilite manualmente o serviço Controle remoto do JMDroid em Acessibilidade; o app oferece um atalho para abrir essa tela. A captura de tela também pede o consentimento próprio do Android.

## Gerar APK

Abra esta pasta no Android Studio, aguarde a sincronização do Gradle e selecione **Build > Build APK(s)**. Também é possível compilar pela linha de comando em um ambiente com JDK 17, Android SDK 35 e Gradle 8.11.1:

```sh
gradle assembleDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`. Também há uma URL de download na release pública mais recente após a primeira versão ser publicada: <https://github.com/deggau/jmdDroid/releases/latest/download/jmdroid.apk>. No POCO X3, baixe o arquivo e toque nele para instalar; o Android pode pedir permissão para instalar apps pelo navegador.

O workflow no GitHub Actions também publica o APK debug como artefato de cada execução.
