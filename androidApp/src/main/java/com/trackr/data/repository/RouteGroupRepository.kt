package com.trackr.data.repository

import android.content.Context
import android.util.Log
import com.trackr.core.data.local.FavoritesStore
import com.trackr.core.data.repository.DefaultFavoritesRepository
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import com.trackr.data.local.SharedPrefsFavoritesStore
import com.trackr.data.local.SharedPrefsRouteGroupStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 路线和收藏分组（旧接口 [IRouteGroupRepository]）。
 *
 * 第 14 步起只是一层转调：数据都在 [DefaultFavoritesRepository]（收藏合一），和 TransitRepository 的收藏的车是同一份。
 * 规划、路线详情、编辑收藏的 ViewModel 和 `RouteGroupSaver` 仍经 [IRouteGroupRepository] 读写路线，所以保留。
 */
class RouteGroupRepository(private val favorites: DefaultFavoritesRepository) : IRouteGroupRepository {

    /** 旧存储（SharedPreferences `trackr_prefs` + `trackr_route_groups`）；现有测试用 */
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(SharedPrefsFavoritesStore(context), ioDispatcher)

    /**
     * 用给定的存储自己建一个收藏仓库（测试里传 RoomFavoritesStore）。
     * 和旧版一样在调用线程上读一次，之后的改动才走 [ioDispatcher]；这样传 StandardTestDispatcher 也不会卡住。
     */
    constructor(store: FavoritesStore, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(openFavoritesLoadingInline(store, ioDispatcher))

    override val groups: StateFlow<List<TransitGroup>> get() = favorites.routes

    override val favoriteGroups: StateFlow<List<FavoriteGroup>> get() = favorites.favoriteGroups

    override suspend fun addGroup(group: TransitGroup) = favorites.saveRoute(group)

    override suspend fun updateGroup(group: TransitGroup) = favorites.updateRoute(group)

    override suspend fun deleteGroup(groupId: String) = favorites.removeRoute(groupId)

    override suspend fun saveOrConvertTemporaryGroup(
        groupId: String,
        newName: String?,
        newCategory: GroupCategory?,
        customGroupId: String?
    ) = favorites.saveOrConvertTemporaryRoute(groupId, newName, newCategory, customGroupId)

    override suspend fun reorderGroups(groupOrderIds: List<String>) = favorites.reorderRoutes(groupOrderIds)

    override fun getGroupById(groupId: String): TransitGroup? = favorites.getRoute(groupId)

    override suspend fun createFavoriteGroup(name: String): FavoriteGroup = favorites.createGroup(name)

    override suspend fun renameFavoriteGroup(groupId: String, newName: String) = favorites.renameGroup(groupId, newName)

    override suspend fun deleteFavoriteGroup(groupId: String) = favorites.deleteGroup(groupId)

    override suspend fun reorderFavoriteGroups(groupIds: List<String>) = favorites.reorderGroups(groupIds)

    override suspend fun addCandidateLineToLeg(groupId: String, legIndex: Int, candidateLine: CandidateLine) =
        favorites.addCandidateLineToLeg(groupId, legIndex, candidateLine)

    companion object {
        private const val TAG = "FavoritesRepository"

        /** 旧存储里损坏 JSON 的备份 key 后缀（实现在 [SharedPrefsRouteGroupStore]） */
        internal const val CORRUPT_BACKUP_SUFFIX = SharedPrefsRouteGroupStore.CORRUPT_BACKUP_SUFFIX

        /** 同步读一次存储建好收藏仓库（读盘在 [ioDispatcher] 上，调用线程等它做完）；写盘失败记日志。应用里用这个 */
        fun openFavorites(
            store: FavoritesStore,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): DefaultFavoritesRepository = runBlocking {
            DefaultFavoritesRepository.create(store, ioDispatcher)
        }.withLogging()

        /** 读盘在调用线程上做（runBlocking 自己的事件循环），之后的改动走 [ioDispatcher] */
        private fun openFavoritesLoadingInline(
            store: FavoritesStore,
            ioDispatcher: CoroutineDispatcher
        ): DefaultFavoritesRepository = runBlocking {
            DefaultFavoritesRepository.create(store, ioDispatcher, loadContext = EmptyCoroutineContext)
        }.withLogging()

        private fun DefaultFavoritesRepository.withLogging() = apply {
            onWriteError = { Log.e(TAG, "Failed to persist favorites", it) }
        }
    }
}
