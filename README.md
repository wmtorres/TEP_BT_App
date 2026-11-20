# ESP32 Monitor Bluetooth

Aplicativo Android nativo para receber linhas de texto por Bluetooth clássico SPP/RFCOMM. O app funciona como servidor e aguarda uma conexão de entrada usando o UUID serial padrão `00001101-0000-1000-8000-00805F9B34FB`.

## Uso

1. Instale e abra o app; conceda as permissões Bluetooth.
2. Deixe a recepção iniciada. O status deve mostrar que está aguardando conexão Bluetooth SPP.
3. No dispositivo cliente, conecte ao serviço serial deste celular. O app cliente precisa suportar conexão SPP/RFCOMM de saída.
4. Envie texto terminado em newline (`\\n` ou `\\r\\n`) para que cada linha apareça no registro.

O pareamento sozinho não estabelece a conexão. Ambos os dispositivos precisam oferecer Bluetooth clássico compatível, e o terminal remoto precisa conectar ao serviço SPP anunciado por este app. Esta implementação é para Bluetooth clássico; BLE requer outro protocolo.
