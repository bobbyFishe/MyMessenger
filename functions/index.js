const functions = require('firebase-functions');
const admin = require('firebase-admin');
admin.initializeApp();

exports.sendNotificationOnNewMessage = functions.database
  .ref('/transit_messages/{chatId}/{messageId}')
  .onCreate(async (snapshot, context) => {
    const message = snapshot.val();
    const { chatId } = context.params;
    const senderId = message.senderId;

    console.log('📨 Новое сообщение в чате:', chatId);

    // Определяем получателя
    const uids = chatId.split('_');
    const recipientId = uids.find(uid => uid !== senderId);

    if (!recipientId) {
      console.log('ℹ️ Self-chat или получатель не найден');
      return null;
    }

    try {
      // Получаем FCM токен получателя
      const tokenSnapshot = await admin.database()
        .ref(`users/${recipientId}/fcmToken`)
        .once('value');

      const token = tokenSnapshot.val();

      if (!token) {
        console.log('❌ Нет FCM токена для', recipientId);
        return null;
      }

      console.log('✅ Токен получен для', recipientId);

      // Проверяем статус получателя (опционально)
      const statusSnapshot = await admin.database()
        .ref(`users/${recipientId}/status`)
        .once('value');

      const status = statusSnapshot.val();

      if (status === 'offline') {
        console.log('📴 Пользователь offline, не отправляем');
        return null;
      }

      // Отправляем data-only сообщение
      await admin.messaging().send({
        token: token,
        data: {
          type: 'NEW_MESSAGE',
          chatId: chatId,
          senderId: senderId,
          text: 'Новое сообщение'
        },
        android: {
          priority: 'high'
        }
      });

      console.log('✅ Уведомление отправлено для', recipientId);

    } catch (error) {
      console.error('❌ Ошибка отправки уведомления:', error);
    }

    return null;
  });

// Обработка системных сообщений (сброс ключей)
exports.sendSystemNotification = functions.database
  .ref('/transit_messages/{chatId}/{messageId}')
  .onCreate(async (snapshot, context) => {
    const message = snapshot.val();

    // Проверяем, системное ли это сообщение
    if (message.encryptedText === 'SYSTEM_KEY_RESET') {
      console.log('🔄 Обнаружен сброс ключей, отправляем уведомление');

      const { chatId } = context.params;
      const senderId = message.senderId;
      const uids = chatId.split('_');
      const recipientId = uids.find(uid => uid !== senderId);

      if (recipientId) {
        const tokenSnapshot = await admin.database()
          .ref(`users/${recipientId}/fcmToken`)
          .once('value');

        const token = tokenSnapshot.val();

        if (token) {
          await admin.messaging().send({
            token: token,
            data: {
              type: 'SYSTEM_KEY_RESET',
              chatId: chatId,
              senderId: senderId
            },
            android: {
              priority: 'high'
            }
          });
          console.log('✅ Уведомление о сбросе ключей отправлено');
        }
      }
    }

    return null;
  });