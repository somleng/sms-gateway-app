## Architecture

Somleng SMS Gateway is composed of three main parts: **Client Application**, **Somleng**, and the **SMS Gateway App** on Android. The SMS Gateway App maintains a persistent WebSocket connection to Somleng and uses the device’s SIM card to send and receive SMS, acting as a bridge between Somleng and the mobile network.

### Send a Message

<img src="../assets/diagrams/outbound-message.png" alt="Somleng SMS Gateway - Send a Message" width="800" />

The outbound flow sends an SMS from your application to a recipient via Somleng and the SMS Gateway App:

1.  **Client Application → Somleng**: The client application sends a new outbound message request to Somleng's API.
2.  **Somleng → SMS Gateway App (WebSocket)**: Somleng pushes a `message_send_request` WebSocket message to the SMS Gateway App associated with the target device.
3.  **App → Somleng**: To ensure no other devices pick up the same message, the app sends a `message_send_requested` WebSocket message to Somleng.
4.  **Somleng → App**: Somleng sends a reply `message_send_confirmed` WebSocket message to the app, confirming that this device will handle sending the message.
5.  **App → Recipient (SMS)**: After receiving `message_send_confirmed`, the app sends the SMS via the device’s SIM card to the recipient over the mobile network.
6.  **App → Somleng (status update)**: After the SMS is sent, the app sends a `sent` WebSocket message so Somleng can update the delivery status for the client application.

### Receive a Message

<img src="../assets/diagrams/inbound-message.png" alt="Somleng SMS Gateway - Receive a Message" width="800" />

The inbound flow delivers SMS messages received on the device to your application via Somleng:

1.  **Sender → Device (SMS)**: An external sender sends an SMS message to the phone number of the device running the SMS Gateway App.
2.  **Device → SMS Gateway App**: The Android device receives the SMS message, and the SMS Gateway App detects the new inbound message.
3.  **SMS Gateway App → Somleng (WebSocket)**: The app sends a `received` WebSocket message to Somleng, including the message details.
4.  **Somleng → Client Application (HTTP webhook)**: Somleng creates an inbound message and invokes the configured HTTP webhook on the registered phone number on Somleng.
5.  **Client Application**: The client responds to Somleng with TwiML, which contains the instructions for handling the incoming message.
