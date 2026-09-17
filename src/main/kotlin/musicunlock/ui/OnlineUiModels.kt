package musicunlock.ui

import musicunlock.online.MusicAccount
import musicunlock.settings.AccountSnapshot

internal fun AccountSnapshot.toMusicAccount() = MusicAccount(
    nickname = nickname,
    avatarUrl = avatarUrl,
    userId = userId,
)

internal fun MusicAccount.toSnapshot() = AccountSnapshot(
    nickname = nickname,
    avatarUrl = avatarUrl,
    userId = userId,
)
