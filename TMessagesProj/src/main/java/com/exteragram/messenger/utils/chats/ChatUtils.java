package com.exteragram.messenger.utils.chats;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

public class ChatUtils {

    private static final ChatUtils instance = new ChatUtils();

    public static ChatUtils getInstance() {
        return instance;
    }

    // Resolves a public channel/group by username; callback gets the chat or null, on the UI thread.
    public void resolveChannel(String username, Utilities.Callback<TLRPC.Chat> callback) {
        int account = UserConfig.selectedAccount;
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.Chat chat = null;
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                MessagesStorage.getInstance(account).putUsersAndChats(res.users, res.chats, false, true);
                if (!res.chats.isEmpty()) {
                    chat = res.chats.get(0);
                }
            }
            if (callback != null) {
                callback.run(chat);
            }
        }));
    }
}
