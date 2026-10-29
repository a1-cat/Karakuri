package io.oyasai.karakuri.engine

import io.oyasai.karakuri.KarakuriPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.RedstoneRail
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 2台のトロッコ間の連結データ
 */
data class MinecartLink(
  val id: String = UUID.randomUUID().toString(),
  val cartA: Minecart,
  val cartB: Minecart,
  val distance: Double, // 連結時の設定間隔 (最大16.0)
  var chainDisplay: BlockDisplay? = null,
  var interaction: Interaction? = null,
)

/**
 * 軌道履歴ノード
 */
data class PathNode(
  val x: Double,
  val y: Double,
  val z: Double,
  val cumulativeDist: Double,
)

/**
 * 連結されたトロッコ群（列車編成）
 */
class TrainComposition(
  val id: String = UUID.randomUUID().toString(),
  val carts: MutableList<Minecart> = mutableListOf(),
  val targetDistances: MutableList<Double> = mutableListOf(), // carts[i] と carts[i+1] の距離
) {
  // 軌道履歴 (先頭車両が走行したレール座標の記録)
  val pathHistory = ArrayDeque<PathNode>()
  var totalTravelDist: Double = 0.0
  var lastLeadX: Double = 0.0
  var lastLeadY: Double = 0.0
  var lastLeadZ: Double = 0.0
  var isDerailStopped: Boolean = false
  var reverseCooldown: Int = 0 // 折り返し反転のクールダウン (ticks)
  var derailCounter: Int = 0 // 脱線判定の猶予カウンター (ticks)
  var wasPoweredRailActive: Boolean = false // 前tickのパワードレール通電状態（エッジトリガー自動発進用）
}

/**
 * トロッコ連結（列車編成）管理マネージャー
 */
class MinecartCouplingManager(private val plugin: KarakuriPlugin) {

  companion object {
    const val MAX_COUPLING_DISTANCE = 16.0
    const val TAG_CHAIN_LINK = "karakuri:chain_link"
    const val TAG_LINK_ID_PREFIX = "karakuri:link_id:"

    fun isRail(mat: Material): Boolean {
      return mat == Material.RAIL ||
             mat == Material.POWERED_RAIL ||
             mat == Material.DETECTOR_RAIL ||
             mat == Material.ACTIVATOR_RAIL
    }
  }

  // プレイヤーごとの連結モード選択セッション (Player UUID -> 1両目 Minecart)
  val selectingPlayers = ConcurrentHashMap<UUID, Minecart>()

  // リンクID -> リンク
  val links = ConcurrentHashMap<String, MinecartLink>()

  // 編成リスト
  val trains = mutableListOf<TrainComposition>()

  // プレイヤーごとのトロッコ手押しモード (Player UUID -> Boolean, デフォルト: false)
  val playerPushMode = ConcurrentHashMap<UUID, Boolean>()

  fun isPushModeEnabled(player: Player): Boolean {
    return playerPushMode.getOrDefault(player.uniqueId, false)
  }

  fun setPushModeEnabled(player: Player, enabled: Boolean) {
    playerPushMode[player.uniqueId] = enabled
  }

  fun togglePushMode(player: Player): Boolean {
    val current = isPushModeEnabled(player)
    val next = !current
    playerPushMode[player.uniqueId] = next
    return next
  }

  // からくりレール走行中のトロッコ集合（重力オフ管理用）
  val cartsOnKarakuriRail = mutableSetOf<Minecart>()

  /**
   * トロッコが属する列車編成を取得
   */
  fun getTrainForCart(cart: Minecart): TrainComposition? {
    return trains.firstOrNull { it.carts.contains(cart) }
  }

  /**
   * 2台のトロッコが既に同じ列車編成（連結成分）に属しているか判定
   */
  fun areCartsInSameTrain(cartA: Minecart, cartB: Minecart): Boolean {
    if (cartA.uniqueId == cartB.uniqueId) return true
    val visited = mutableSetOf<UUID>()
    val queue = ArrayDeque<Minecart>()
    queue.add(cartA)
    visited.add(cartA.uniqueId)

    while (queue.isNotEmpty()) {
      val curr = queue.poll()
      if (curr.uniqueId == cartB.uniqueId) return true
      for (link in links.values) {
        val neighbor = if (link.cartA == curr) link.cartB else if (link.cartB == curr) link.cartA else null
        if (neighbor != null && neighbor.isValid && !visited.contains(neighbor.uniqueId)) {
          visited.add(neighbor.uniqueId)
          queue.add(neighbor)
        }
      }
    }
    return false
  }

  /**
   * トロッコが属する連結成分内の全トロッコを取得
   */
  fun getConnectedComponent(cart: Minecart): Set<Minecart> {
    val component = mutableSetOf<Minecart>()
    if (!cart.isValid) return component
    val queue = ArrayDeque<Minecart>()
    queue.add(cart)
    component.add(cart)

    while (queue.isNotEmpty()) {
      val curr = queue.poll()
      for (link in links.values) {
        val neighbor = if (link.cartA == curr) link.cartB else if (link.cartB == curr) link.cartA else null
        if (neighbor != null && neighbor.isValid && !component.contains(neighbor)) {
          component.add(neighbor)
          queue.add(neighbor)
        }
      }
    }
    return component
  }

  /**
   * トロッコが属する編成全体（連結グラフ）にからくり付きトロッコが含まれるか判定
   */
  fun trainContainsKarakuri(cart: Minecart): Boolean {
    if (plugin.manager.isKarakuriMinecart(cart)) return true
    val comp = getConnectedComponent(cart)
    return comp.any { plugin.manager.isKarakuriMinecart(it) }
  }

  /**
   * トロッコの現在の直接連結数を取得 (0〜2)
   */
  fun getCouplingDegree(cart: Minecart): Int {
    return links.values.count { (it.cartA == cart || it.cartB == cart) && it.cartA.isValid && it.cartB.isValid }
  }

  /**
   * レンチ左クリック等により、指定したトロッコを先頭車両に設定する
   * 編成の両端のトロッコでのみ有効
   */
  fun setLeadCart(cart: Minecart): Boolean {
    val train = getTrainForCart(cart) ?: return false
    val first = train.carts.first()
    val last = train.carts.last()

    if (cart == first) {
      return true // 既に先頭
    }
    if (cart == last) {
      reverseTrain(train)
      return true
    }
    // 中間車両は先頭にできない
    return false
  }

  /**
   * トロッコの実体エンティティが置換された際（からくり合体・解除・復元時等）に参照を引き継ぐ
   */
  fun replaceCartReference(oldCart: Minecart, newCart: Minecart) {
    if (oldCart.uniqueId == newCart.uniqueId) return

    // 1. links の差し替え
    for ((linkId, link) in links) {
      if (link.cartA == oldCart || link.cartB == oldCart) {
        val newA = if (link.cartA == oldCart) newCart else link.cartA
        val newB = if (link.cartB == oldCart) newCart else link.cartB
        val updatedLink = MinecartLink(
          id = link.id,
          cartA = newA,
          cartB = newB,
          distance = link.distance,
          chainDisplay = link.chainDisplay,
          interaction = link.interaction
        )
        links[linkId] = updatedLink
      }
    }

    // 2. trains の差し替え
    for (train in trains) {
      val idx = train.carts.indexOf(oldCart)
      if (idx != -1) {
        train.carts[idx] = newCart
      }
    }

    // 3. selectingPlayers の差し替え
    for ((uuid, cart) in selectingPlayers) {
      if (cart == oldCart) {
        selectingPlayers[uuid] = newCart
      }
    }
  }

  /**
   * 2台のトロッコを連結
   */
  fun linkCarts(cartA: Minecart, cartB: Minecart): MinecartLink? {
    if (!cartA.isValid || !cartB.isValid) return null
    if (cartA.world != cartB.world) return null
    if (cartA.uniqueId == cartB.uniqueId) return null

    // 1. 各トロッコの接続数制限 (最大2台: 中間車両にさらに繋ぐ分岐を防止)
    if (getCouplingDegree(cartA) >= 2 || getCouplingDegree(cartB) >= 2) {
      return null
    }

    // 2. 閉路チェック: 既に同一編成内のトロッコ同士を繋いで輪（ループ）を作るのを完全防止！
    if (areCartsInSameTrain(cartA, cartB)) {
      return null
    }

    val directDist = cartA.location.distance(cartB.location)
    if (directDist > MAX_COUPLING_DISTANCE) return null

    // 接続前の先頭車両の情報を記憶（先頭継承のため）
    val trainA = getTrainForCart(cartA)
    val trainB = getTrainForCart(cartB)
    val wasALead = trainA?.carts?.firstOrNull() == cartA
    val wasBLead = trainB?.carts?.firstOrNull() == cartB

    val distance = directDist.coerceIn(0.5, MAX_COUPLING_DISTANCE)
    val link = MinecartLink(cartA = cartA, cartB = cartB, distance = distance)
    links[link.id] = link

    // 視覚的な鎖 Display / Interaction の生成
    createChainDisplay(link)

    // 編成グラフの再構築（先頭継承ルール適用）
    val preferredLead = if (wasALead) cartB else if (wasBLead) cartA else (trainA?.carts?.firstOrNull() ?: trainB?.carts?.firstOrNull())
    rebuildTrains(preferredLead = preferredLead)

    return link
  }

  /**
   * 連結の解除
   */
  fun unlink(linkId: String): Boolean {
    val link = links.remove(linkId) ?: return false

    // エンティティ削除
    link.chainDisplay?.let { if (it.isValid) it.remove() }
    link.interaction?.let { if (it.isValid) it.remove() }

    val midLoc = link.cartA.location.clone().add(link.cartB.location).multiply(0.5)
    val pLoc = midLoc.clone().add(0.0, 0.35, 0.0)
    midLoc.world?.spawnParticle(Particle.BLOCK, pLoc.x, pLoc.y, pLoc.z, 20, 0.2, 0.2, 0.2, 0.05, Material.IRON_CHAIN.createBlockData())

    rebuildTrains()
    return true
  }

  /**
   * 特定トロッコに関連するすべての連結を解除
   */
  fun unlinkCart(cart: Minecart) {
    val toRemove = links.values.filter { it.cartA == cart || it.cartB == cart }
    for (l in toRemove) {
      unlink(l.id)
    }
  }

  /**
   * 鎖の BlockDisplay & Interaction エンティティの生成
   */
  private fun createChainDisplay(link: MinecartLink) {
    val world = link.cartA.world
    val locA = link.cartA.location.clone().add(0.0, 0.35, 0.0)
    val locB = link.cartB.location.clone().add(0.0, 0.35, 0.0)
    val midLoc = locA.clone().add(locB).multiply(0.5)

    // BlockDisplay は始点 locA (Yaw=0, Pitch=0) に生成
    val spawnLocA = Location(world, locA.x, locA.y, locA.z, 0.0f, 0.0f)
    val display = world.spawn(spawnLocA, BlockDisplay::class.java) { bd ->
      bd.block = Material.IRON_CHAIN.createBlockData()
      bd.brightness = Display.Brightness(15, 15)
      bd.teleportDuration = 0
      bd.interpolationDuration = 0
      bd.addScoreboardTag(TAG_CHAIN_LINK)
      bd.addScoreboardTag("$TAG_LINK_ID_PREFIX${link.id}")
    }

    // Interaction はクリックしやすいように中間地点に配置
    val inter = world.spawn(midLoc, Interaction::class.java) { it ->
      it.interactionWidth = 0.8f
      it.interactionHeight = 0.8f
      it.addScoreboardTag(TAG_CHAIN_LINK)
      it.addScoreboardTag("$TAG_LINK_ID_PREFIX${link.id}")
    }

    link.chainDisplay = display
    link.interaction = inter

    updateChainDisplay(link)
  }

  /**
   * 鎖の姿勢・スケール・位置を2台のトロッコ間に正確に同期
   * 始点 locA から 終点 locB へ向けて精密にクォータニオン回転と中心オフセットを適用
   */
  private fun updateChainDisplay(link: MinecartLink) {
    val display = link.chainDisplay ?: return
    val inter = link.interaction ?: return
    if (!display.isValid || !inter.isValid || !link.cartA.isValid || !link.cartB.isValid) return

    val locA = link.cartA.location.clone().add(0.0, 0.35, 0.0)
    val locB = link.cartB.location.clone().add(0.0, 0.35, 0.0)
    val midLoc = locA.clone().add(locB).multiply(0.5)

    // Interaction は2両の中間地点に追従
    inter.teleport(midLoc)

    // BlockDisplay は必ず Yaw=0, Pitch=0 で始点 locA に配置 (二重回転を完全防止！)
    display.teleportDuration = 0
    display.interpolationDuration = 0
    display.teleport(Location(link.cartA.world, locA.x, locA.y, locA.z, 0.0f, 0.0f))

    // AからBへの方向ベクトル
    val dir = locB.toVector().subtract(locA.toVector())
    val length = dir.length().toFloat().coerceAtLeast(0.1f)
    val normDir = if (length > 0.0001f) dir.multiply(1.0 / length) else Vector(0, 1, 0)

    // バニラCHAINブロックはローカルY軸 (0, 1, 0) に伸びている
    val vFrom = Vector(0.0, 1.0, 0.0)
    val vTo = normDir
    val cross = vFrom.clone().crossProduct(vTo)
    val dot = vFrom.dot(vTo).coerceIn(-1.0, 1.0)

    val quat = if (dot > 0.9999) {
      Quaternionf()
    } else if (dot < -0.9999) {
      Quaternionf().rotateX(Math.PI.toFloat())
    } else {
      val angle = acos(dot).toFloat()
      val axis = Vector3f(cross.x.toFloat(), cross.y.toFloat(), cross.z.toFloat()).normalize()
      Quaternionf().set(AxisAngle4f(angle, axis))
    }

    val chainThickness = 0.2f
    val scale = Vector3f(chainThickness, length, chainThickness)
    // ブロックモデルの中心線 (0.5, 0, 0.5) を回転軸 (0, 0, 0) に揃えるためのオフセット
    val localCenterOffset = Vector3f(-0.5f * chainThickness, 0.0f, -0.5f * chainThickness)
    val rotatedTranslation = Vector3f(localCenterOffset).rotate(quat)

    display.transformation = Transformation(
      rotatedTranslation,
      quat,
      scale,
      Quaternionf()
    )
  }

  /**
   * リンク関係から連結グラフ（列車編成）を再構築
   */
  fun rebuildTrains(preferredLead: Minecart? = null) {
    val existingLeads = trains.mapNotNull { it.carts.firstOrNull() }.toSet()
    trains.clear()

    // 有効なリンクのみ抽出
    val validLinks = links.values.filter { it.cartA.isValid && it.cartB.isValid }
    if (validLinks.isEmpty()) return

    // 隣接グラフ作成
    val adj = mutableMapOf<Minecart, MutableList<Pair<Minecart, Double>>>()
    for (l in validLinks) {
      adj.computeIfAbsent(l.cartA) { mutableListOf() }.add(Pair(l.cartB, l.distance))
      adj.computeIfAbsent(l.cartB) { mutableListOf() }.add(Pair(l.cartA, l.distance))
    }

    val visited = mutableSetOf<Minecart>()

    for (startCart in adj.keys) {
      if (startCart in visited) continue

      val component = mutableSetOf<Minecart>()
      val queue = ArrayDeque<Minecart>()
      queue.add(startCart)
      component.add(startCart)

      while (queue.isNotEmpty()) {
        val curr = queue.poll()
        for ((neighbor, _) in adj[curr] ?: emptyList()) {
          if (neighbor !in component) {
            component.add(neighbor)
            queue.add(neighbor)
          }
        }
      }

      visited.addAll(component)

      // ★重要: 通常トロッコのみの連結は成立しない！
      // からくりトロッコが1両も含まれていない編成になった場合、その編成のすべての連結を自動解除する
      val hasKarakuri = component.any { trainContainsKarakuri(it) }
      if (!hasKarakuri) {
        val toRemove = links.values.filter { it.cartA in component || it.cartB in component }
        for (l in toRemove) {
          links.remove(l.id)
          l.chainDisplay?.let { if (it.isValid) it.remove() }
          l.interaction?.let { if (it.isValid) it.remove() }
          val midLoc = l.cartA.location.clone().add(l.cartB.location).multiply(0.5)
          val pLoc = midLoc.clone().add(0.0, 0.35, 0.0)
          midLoc.world?.spawnParticle(Particle.BLOCK, pLoc.x, pLoc.y, pLoc.z, 20, 0.2, 0.2, 0.2, 0.05, Material.IRON_CHAIN.createBlockData())
        }
        continue
      }

      // 端の車両（接続数が1のもの）を先頭として探索開始
      val endCarts = component.filter { (adj[it]?.size ?: 0) <= 1 }
      val endCart = if (preferredLead != null && preferredLead in endCarts) {
        preferredLead
      } else {
        endCarts.firstOrNull { it in existingLeads } ?: endCarts.firstOrNull() ?: component.first()
      }

      // 線形パスを復元
      val orderedCarts = mutableListOf<Minecart>()
      val orderedDistances = mutableListOf<Double>()
      val pathVisited = mutableSetOf<Minecart>()

      var cur: Minecart? = endCart
      while (cur != null) {
        orderedCarts.add(cur)
        pathVisited.add(cur)

        val nextEdge = adj[cur]?.firstOrNull { it.first !in pathVisited }
        if (nextEdge != null) {
          orderedDistances.add(nextEdge.second)
          cur = nextEdge.first
        } else {
          cur = null
        }
      }

      if (orderedCarts.size >= 2) {
        val train = TrainComposition(carts = orderedCarts, targetDistances = orderedDistances)
        initTrainPath(train)
        trains.add(train)
      }
    }
  }

  /**
   * 列車編成の初期経路履歴を設定
   */
  fun initTrainPath(train: TrainComposition) {
    train.pathHistory.clear()
    val lead = train.carts.first()
    train.lastLeadX = lead.location.x
    train.lastLeadY = lead.location.y
    train.lastLeadZ = lead.location.z

    var cumDist = 0.0
    for (i in 0 until train.carts.size) {
      val c = train.carts[i]
      if (i > 0) {
        cumDist += train.targetDistances.getOrElse(i - 1) { 1.5 }
      }
      train.pathHistory.addLast(PathNode(c.location.x, c.location.y, c.location.z, -cumDist))
    }
    train.totalTravelDist = 0.0
    // 初期化時に現在のパワードレール通電状態を記憶し、連結直後の誤発進を防止
    train.wasPoweredRailActive = train.carts.any { isCartOnPoweredRail(it) }
  }

  /**
   * 先頭車両と最後尾車両の入れ替え（進行方向反転 / スイッチバック）
   */
  fun reverseTrain(train: TrainComposition) {
    if (train.carts.size < 2) return
    train.carts.reverse()
    train.targetDistances.reverse()

    // 速度ベクトルの反転
    for (cart in train.carts) {
      if (cart.velocity.lengthSquared() > 0.0001) {
        cart.velocity = cart.velocity.multiply(-1.0)
      }
    }

    initTrainPath(train)
    train.reverseCooldown = 25 // 約1.25秒間クールダウン

    val newLead = train.carts.first()
    newLead.world.spawnParticle(Particle.CRIT, newLead.location.clone().add(0.0, 0.5, 0.0), 20, 0.3, 0.3, 0.3, 0.1)
  }

  /**
   * 毎tickの列車走行物理・追従・脱線検知・からくりレールガイド処理
   */
  fun tick() {
    // 1. リンクの無効化チェック
    val deadLinks = links.values.filter { !it.cartA.isValid || !it.cartB.isValid || it.cartA.world != it.cartB.world }
    for (d in deadLinks) {
      unlink(d.id)
    }

    // 2. 鎖ディスプレイの姿勢更新
    for (link in links.values) {
      updateChainDisplay(link)
    }

    // ★重要: からくりレール上の全トロッコ（単車・編成・一般トロッコ問わず）を物理ガイド！床抜けを完全防止
    for (world in Bukkit.getWorlds()) {
      for (cart in world.getEntitiesByClass(Minecart::class.java)) {
        guideCartOnKarakuriRail(cart)
      }
    }

    // 3. 各列車編成の追従・物理処理
    for (train in trains) {
      tickTrain(train)
    }

    // 4. 選択中プレイヤーへの視覚的案内（パーティクル＆アクションバー）
    tickSelectingPlayers()
  }

  private fun tickSelectingPlayers() {
    if (selectingPlayers.isEmpty()) return
    for ((uuid, cart) in selectingPlayers) {
      val player = Bukkit.getPlayer(uuid) ?: continue
      if (!player.isOnline || !cart.isValid) continue
      val pLoc = cart.location.clone().add(0.0, 0.6, 0.0)
      cart.world.spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 2, 0.2, 0.2, 0.2, 0.0)
      player.sendActionBar(Component.text("⛓️ 【トロッコ連結モード】 2両目のトロッコを鉄の鎖で右クリックしてください (解除: Shift左クリック)", NamedTextColor.YELLOW))
    }
  }

  private fun tickTrain(train: TrainComposition) {
    if (train.carts.size < 2) return

    if (train.reverseCooldown > 0) {
      train.reverseCooldown--
    }

    // (A) からくり化されたレール上のトロッコを物理ガイド (落下防止・レール吸着)
    for (cart in train.carts) {
      guideCartOnKarakuriRail(cart)
    }

    // (B) 脱線判定: 編成内のいずれか1台でもレール上（バニラまたはからくりレール）にない場合は即時停止
    var isDerailed = false
    for (cart in train.carts) {
      if (!isCartOnRail(cart)) {
        isDerailed = true
        break
      }
    }

    if (isDerailed) {
      train.derailCounter++
      if (train.derailCounter >= 6) { // 約0.3秒間連続でレールから完全に外れた場合のみ脱線停止
        if (!train.isDerailStopped) {
          train.isDerailStopped = true
          for (cart in train.carts) {
            cart.velocity = Vector(0.0, 0.0, 0.0)
            cart.world.spawnParticle(Particle.SMOKE, cart.location.add(0.0, 0.5, 0.0), 15, 0.2, 0.2, 0.2, 0.05)
          }
        } else {
          // 脱線中は完全に停止維持
          for (cart in train.carts) {
            cart.velocity = Vector(0.0, 0.0, 0.0)
          }
        }
        return
      }
    } else {
      train.derailCounter = 0
      train.isDerailStopped = false
    }

    // (B-2) パワードレール通電による自動発進:
    // 非通電パワードレールで停止していた編成に対し、信号が再入力された瞬間（OFF→ONエッジ）にのみ先頭車両の方向へ自動発進！
    val leadCart = train.carts.first()
    var leadVel = leadCart.velocity
    var leadSpeed = leadVel.length()
    val isPoweredActive = train.carts.any { isCartOnPoweredRail(it) }
    val poweredJustTurnedOn = isPoweredActive && !train.wasPoweredRailActive
    train.wasPoweredRailActive = isPoweredActive

    if (leadSpeed <= 0.02 && poweredJustTurnedOn) {
      val forwardDir = if (train.carts.size >= 2) {
        val secondCart = train.carts[1]
        val dir = leadCart.location.toVector().subtract(secondCart.location.toVector()).setY(0.0)
        if (dir.lengthSquared() > 0.001) dir.normalize() else getCartForwardDirection(leadCart)
      } else {
        getCartForwardDirection(leadCart)
      }
      val launchVec = alignVectorToRail(leadCart.location, forwardDir, 0.4)
      leadCart.velocity = launchVec
      leadVel = launchVec
      leadSpeed = launchVec.length()
      initTrainPath(train)
    }

    // (C) リーダー（先頭車両）の移動量チェック
    val curLeadX = leadCart.location.x
    val curLeadY = leadCart.location.y
    val curLeadZ = leadCart.location.z

    val dx = curLeadX - train.lastLeadX
    val dy = curLeadY - train.lastLeadY
    val dz = curLeadZ - train.lastLeadZ
    val moveDist = sqrt(dx * dx + dy * dy + dz * dz)
    val isLeadActuallyMoving = moveDist > 0.005

    // ★重要: 先頭車両が端などで進めない場合、後続車両が進み続けようとするのを完全防止！
    if (!isLeadActuallyMoving) {
      for (i in 1 until train.carts.size) {
        train.carts[i].velocity = Vector(0.0, 0.0, 0.0)
      }
    }

    // (D) レールの端に到達した際の自動先頭入れ替え（折り返し運転）
    if (train.reverseCooldown <= 0) {
      val inst = plugin.manager.getKarakuriByMinecart(leadCart)
      val autoReverse = (inst?.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion)?.autoReverseAtRailEnd ?: true
      if (autoReverse) {
        val checkVec = if (isLeadActuallyMoving) Vector(dx, dy, dz) else leadVel
        if (checkVec.lengthSquared() > 0.0001 && isCartAtRailEnd(leadCart, checkVec)) {
          reverseTrain(train)
          return
        }
      }
    }

    if (moveDist > 0.001) {
      train.totalTravelDist += moveDist
      train.pathHistory.addFirst(PathNode(curLeadX, curLeadY, curLeadZ, train.totalTravelDist))

      train.lastLeadX = curLeadX
      train.lastLeadY = curLeadY
      train.lastLeadZ = curLeadZ

      // 古い履歴の削除 (最後尾車両の必要距離 + 10m を超えたもの)
      val totalTargetDist = train.targetDistances.sum()
      val maxNeededDist = train.totalTravelDist - totalTargetDist - 10.0
      while (train.pathHistory.size > 2 && train.pathHistory.last().cumulativeDist < maxNeededDist) {
        train.pathHistory.removeLast()
      }
    }

    // (E) 後続車両のレール経路履歴追従
    var cumOffset = 0.0
    for (i in 1 until train.carts.size) {
      val follower = train.carts[i]
      cumOffset += train.targetDistances.getOrElse(i - 1) { 1.5 }
      val targetCumDist = train.totalTravelDist - cumOffset

      val targetPos = interpolatePath(train.pathHistory, targetCumDist)
      if (targetPos != null) {
        val targetLoc = Location(follower.world, targetPos.x.toDouble(), targetPos.y.toDouble(), targetPos.z.toDouble(), follower.location.yaw, follower.location.pitch)

        val distToTarget = follower.location.distance(targetLoc)
        if (isLeadActuallyMoving) {
          val targetVel = targetLoc.toVector().subtract(follower.location.toVector())
          if (leadSpeed > 0.01) {
            val normVel = targetVel.clone().normalize().multiply(leadSpeed)
            follower.velocity = normVel
          }
          if (distToTarget > 0.2) {
            follower.teleport(targetLoc)
          }
        } else {
          follower.velocity = Vector(0.0, 0.0, 0.0)
          if (distToTarget > 0.3) {
            follower.teleport(targetLoc)
          }
        }
      }
    }
  }

  /**
   * レール終端判定: カーブを誤判定せず、真の行き止まりのみを検出
   */
  private fun isCartAtRailEnd(cart: Minecart, moveVec: Vector): Boolean {
    if (moveVec.lengthSquared() < 0.0001) return false
    val loc = cart.location

    // 1. 現在足元のレールがカーブ形状なら、曲がり角を走行中なので絶対に終端ではない！
    val curRail = getRailDataAt(loc)
    if (curRail != null && isCurveShape(curRail.shape)) {
      return false
    }

    // 2. 直進方向（0.7m先）にレールがあれば終端ではない
    val norm = moveVec.clone().normalize().multiply(0.7)
    val aheadLoc = loc.clone().add(norm)
    if (hasRailAt(aheadLoc)) {
      return false
    }

    // 3. 直進方向にレールがなくても、周囲にカーブレールが接続している場合は曲がり角への進入なので終端ではない！
    val checkOffsets = arrayOf(
      intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
      intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
    )
    for (off in checkOffsets) {
      val nearLoc = loc.clone().add(off[0] * 0.7, 0.0, off[1] * 0.7)
      val nearRail = getRailDataAt(nearLoc)
      if (nearRail != null && isCurveShape(nearRail.shape)) {
        return false
      }
    }

    // 前方にも周囲のカーブにもレールが一切ない場合、真のレール終端（行き止まり）！
    return true
  }

  private fun isCurveShape(shape: org.bukkit.block.data.Rail.Shape): Boolean {
    return shape == org.bukkit.block.data.Rail.Shape.NORTH_EAST ||
           shape == org.bukkit.block.data.Rail.Shape.NORTH_WEST ||
           shape == org.bukkit.block.data.Rail.Shape.SOUTH_EAST ||
           shape == org.bukkit.block.data.Rail.Shape.SOUTH_WEST
  }

  private fun hasRailAt(l: Location): Boolean {
    val bAt = l.block
    val bBelow = l.clone().add(0.0, -0.4, 0.0).block
    val bSlightBelow = l.clone().add(0.0, -0.1, 0.0).block
    return isRail(bAt.type) || isRail(bBelow.type) || isRail(bSlightBelow.type) ||
           isKarakuriRailAt(l) || isKarakuriRailAt(l.clone().add(0.0, -0.4, 0.0))
  }

  private fun getRailDataAt(l: Location): org.bukkit.block.data.Rail? {
    val bAt = l.block
    if (isRail(bAt.type)) return bAt.blockData as? org.bukkit.block.data.Rail
    val bBelow = l.clone().add(0.0, -0.4, 0.0).block
    if (isRail(bBelow.type)) return bBelow.blockData as? org.bukkit.block.data.Rail
    val bSlightBelow = l.clone().add(0.0, -0.1, 0.0).block
    if (isRail(bSlightBelow.type)) return bSlightBelow.blockData as? org.bukkit.block.data.Rail

    val kRail = findKarakuriRailNear(l)
    return kRail?.blockData as? org.bukkit.block.data.Rail
  }

  /**
   * 軌道履歴から累積距離に応じた位置を線形補間
   */
  private fun interpolatePath(history: ArrayDeque<PathNode>, targetDist: Double): Vector3f? {
    if (history.isEmpty()) return null
    if (history.size == 1) {
      val n = history.first()
      return Vector3f(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
    }

    val list = history.toList()
    for (i in 0 until list.size - 1) {
      val n1 = list[i]
      val n2 = list[i + 1]
      if (targetDist <= n1.cumulativeDist && targetDist >= n2.cumulativeDist) {
        val span = n1.cumulativeDist - n2.cumulativeDist
        val t = if (span > 0.0001) ((targetDist - n2.cumulativeDist) / span).toFloat() else 0f
        val x = (n2.x + (n1.x - n2.x) * t).toFloat()
        val y = (n2.y + (n1.y - n2.y) * t).toFloat()
        val z = (n2.z + (n1.z - n2.z) * t).toFloat()
        return Vector3f(x, y, z)
      }
    }

    val edge = if (targetDist > list.first().cumulativeDist) list.first() else list.last()
    return Vector3f(edge.x.toFloat(), edge.y.toFloat(), edge.z.toFloat())
  }

  data class FoundRail(
    val material: Material,
    val blockData: org.bukkit.block.data.BlockData,
    val railY: Double,
    val railCenterX: Double,
    val railCenterZ: Double,
    val isPowered: Boolean,
    val instance: io.oyasai.karakuri.engine.KarakuriInstance? = null,
    val localBaseLoc: Location? = null,
  )

  /**
   * 指定した座標の足元周辺にあるからくりレールを検出
   */
  fun findKarakuriRailNear(loc: Location): FoundRail? {
    val world = loc.world ?: return null
    val cx = loc.x
    val cy = loc.y
    val cz = loc.z

    for (inst in plugin.manager.getAll()) {
      if (inst.isDormant) continue

      fun getTransformedPos(baseLoc: Location): Triple<Double, Double, Double> {
        val rot = inst.currentRotation
        if (rot.x == 0f && rot.y == 0f && rot.z == 0f && rot.w == 1f) {
          return Triple(baseLoc.blockX + 0.5, baseLoc.blockY.toDouble(), baseLoc.blockZ + 0.5)
        }
        val p = inst.worldPivotLocation
        val rx = (baseLoc.blockX + 0.5 - p.x).toFloat()
        val ry = (baseLoc.blockY + 0.5 - p.y).toFloat()
        val rz = (baseLoc.blockZ + 0.5 - p.z).toFloat()
        val v = org.joml.Vector3f(rx, ry, rz).rotate(rot)
        return Triple(p.x + v.x, p.y + v.y - 0.5, p.z + v.z)
      }

      // 1. 静止・構成ブロック (constituentBlocks)
      for ((bLoc, bd) in inst.constituentBlocks) {
        if (!isRail(bd.material)) continue
        if (bLoc.world != world) continue
        val (rx, ry, rz) = getTransformedPos(bLoc)

        val hDistSq = (cx - rx) * (cx - rx) + (cz - rz) * (cz - rz)
        if (hDistSq <= 0.9 * 0.9 && cy >= ry - 0.6 && cy <= ry + 1.2) {
          val isPow = (bd is RedstoneRail && bd.isPowered)
          return FoundRail(bd.material, bd, ry, rx, rz, isPow, inst, bLoc)
        }
      }

      // 2. ルートブロック (originalBlockData)
      val origBd = inst.originalBlockData
      if (origBd != null && isRail(origBd.material) && inst.originLocation.world == world) {
        val (rx, ry, rz) = getTransformedPos(inst.originLocation)
        val hDistSq = (cx - rx) * (cx - rx) + (cz - rz) * (cz - rz)
        if (hDistSq <= 0.9 * 0.9 && cy >= ry - 0.6 && cy <= ry + 1.2) {
          val isPow = (origBd is RedstoneRail && origBd.isPowered)
          return FoundRail(origBd.material, origBd, ry, rx, rz, isPow, inst, inst.originLocation)
        }
      }

      // 3. 追加パーツ (additionalParts)
      for (part in inst.additionalParts) {
        if (!part.displayEntity.isValid) continue
        val partBd = part.originalBlockData ?: continue
        if (!isRail(partBd.material)) continue
        if (part.originLocation.world != world) continue
        val (rx, ry, rz) = getTransformedPos(part.originLocation)
        val hDistSq = (cx - rx) * (cx - rx) + (cz - rz) * (cz - rz)
        if (hDistSq <= 0.9 * 0.9 && cy >= ry - 0.6 && cy <= ry + 1.2) {
          val isPow = (partBd is RedstoneRail && partBd.isPowered)
          return FoundRail(partBd.material, partBd, ry, rx, rz, isPow, inst, part.originLocation)
        }
      }
    }
    return null
  }

  /**
   * トロッコがレール上（バニラレールまたはからくりレール）に存在するか判定
   */
  fun isCartOnRail(cart: Minecart): Boolean {
    val loc = cart.location
    val bAt = loc.block
    val bBelow = loc.clone().add(0.0, -0.4, 0.0).block
    val bSlightBelow = loc.clone().add(0.0, -0.1, 0.0).block
    if (isRail(bAt.type) || isRail(bBelow.type) || isRail(bSlightBelow.type)) return true

    // カーブ通過時のブロック境界またぎ救済（周囲0.45m近傍を検査）
    val offsets = arrayOf(
      intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
      intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
    )
    for (off in offsets) {
      val checkLoc = loc.clone().add(off[0] * 0.45, 0.0, off[1] * 0.45)
      val bNear = checkLoc.block
      val bNearBelow = checkLoc.clone().add(0.0, -0.4, 0.0).block
      if (isRail(bNear.type) || isRail(bNearBelow.type)) return true
    }

    return findKarakuriRailNear(loc) != null
  }

  /**
   * 指定座標がからくり化されたレールであるかを判定
   */
  fun isKarakuriRailAt(loc: Location): Boolean {
    return findKarakuriRailNear(loc) != null
  }

  /**
   * からくり化されたレール上を走行中のトロッコに対する精密な物理ガイド（滑走・推進・軌道誘導・落下防止・動くからくり追従）
   */
  fun guideCartOnKarakuriRail(cart: Minecart) {
    if (!cart.isValid || cart.isDead) {
      cartsOnKarakuriRail.remove(cart)
      return
    }
    var loc = cart.location
    val rail = findKarakuriRailNear(loc)

    if (rail == null) {
      if (cartsOnKarakuriRail.remove(cart)) {
        cart.setGravity(true)
      }
      return
    }

    // からくりレール上にいる間は重力を無効化（毎tickの落下加速度をゼロにして床抜け・すり抜けを完全阻止）
    cartsOnKarakuriRail.add(cart)
    cart.setGravity(false)

    val inst = rail.instance
    var vel = cart.velocity

    // ★ 1. 動くからくりレール（回転・直線運動）への運搬追従物理 (Carrier Delta & Angular Momentum)
    if (inst != null && inst.activeMotion != null) {
      val collider = plugin.collisionEngine.getCollider(inst)
      val prev = collider.previousState
      val curr = collider.lastState
      if (prev != null && curr != null && collider.isMotionActive) {
        val followedLoc = collider.calculatePlayerFollow(loc, prev, curr)
        val carrierDx = followedLoc.x - loc.x
        val carrierDy = followedLoc.y - loc.y
        val carrierDz = followedLoc.z - loc.z

        val invPrevRot = Quaternionf(prev.rotation).conjugate()
        val deltaQ = Quaternionf(curr.rotation).mul(invPrevRot)
        val rotVel = Vector3f(vel.x.toFloat(), vel.y.toFloat(), vel.z.toFloat()).rotate(deltaQ)
        vel = Vector(rotVel.x.toDouble(), rotVel.y.toDouble(), rotVel.z.toDouble())

        // ★ 台回転等による水平公転Yaw回転量
        val fVec = Vector3f(0f, 0f, 1f).rotate(deltaQ)
        var rotDeltaYaw = (-Math.toDegrees(kotlin.math.atan2(fVec.x.toDouble(), fVec.z.toDouble()))).toFloat()
        while (rotDeltaYaw > 180f) rotDeltaYaw -= 360f
        while (rotDeltaYaw < -180f) rotDeltaYaw += 360f

        val newYaw = (loc.yaw + rotDeltaYaw)
        loc = Location(loc.world, loc.x + carrierDx, loc.y + carrierDy, loc.z + carrierDz, newYaw, loc.pitch)
        cart.teleport(loc)

        // ★ からくり合体トロッコの場合、からくり本体の向きも一緒に回転同期！
        val kInst = plugin.manager.getKarakuriByMinecart(cart)
        val cartMotion = kInst?.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
        if (cartMotion != null && kotlin.math.abs(rotDeltaYaw) > 0.001f) {
          cartMotion.addRotationDelta(rotDeltaYaw)
        }
      }
    }

    val targetY = rail.railY + 0.1
    val yDiff = loc.y - targetY

    // 垂直方向の調整: ズレが大きい場合のみスナップ、通常は微小吸着
    var vy = 0.0
    if (kotlin.math.abs(yDiff) > 0.25) {
      cart.teleport(Location(cart.world, loc.x, targetY, loc.z, loc.yaw, loc.pitch))
    } else {
      vy = (-yDiff * 0.3).coerceIn(-0.15, 0.15)
    }

    val railData = rail.blockData as? org.bukkit.block.data.Rail
    val shape = railData?.shape ?: org.bukkit.block.data.Rail.Shape.NORTH_SOUTH

    val rider = cart.passengers.firstOrNull() as? Player
    val riderYaw = rider?.location?.yaw

    // ★ 2. からくりの姿勢（クォータニオン）に合わせたローカル座標系への射影変換
    val rot = inst?.currentRotation ?: Quaternionf()
    val isRotated = inst != null && (rot.x != 0f || rot.y != 0f || rot.z != 0f)

    if (isRotated && inst != null) {
      val p = inst.worldPivotLocation
      val invRot = Quaternionf(rot).conjugate()

      // トロッコのローカル座標
      val relPos = Vector3f((loc.x - p.x).toFloat(), (loc.y - p.y).toFloat(), (loc.z - p.z).toFloat()).rotate(invRot)
      // レールのローカル中心座標
      val bLoc = rail.localBaseLoc ?: inst.originLocation
      val railLocalCenterX = (bLoc.blockX + 0.5 - p.x).toFloat()
      val railLocalCenterZ = (bLoc.blockZ + 0.5 - p.z).toFloat()

      // トロッコのローカル速度
      val localVel = Vector3f(vel.x.toFloat(), vel.y.toFloat(), vel.z.toFloat()).rotate(invRot)
      var localVx = localVel.x.toDouble()
      var localVz = localVel.z.toDouble()

      // ローカル空間での乗客Yaw
      val localRiderYaw = if (riderYaw != null) {
        val fVec = Vector3f(0f, 0f, 1f).rotate(rot)
        val rotYaw = (-Math.toDegrees(kotlin.math.atan2(fVec.x.toDouble(), fVec.z.toDouble()))).toFloat()
        (riderYaw - rotYaw + 360f) % 360f
      } else null

      when (shape) {
        org.bukkit.block.data.Rail.Shape.NORTH_SOUTH -> {
          val xDiff = relPos.x - railLocalCenterX
          localVx = (-xDiff * 0.25).coerceIn(-0.1, 0.1)
          if (kotlin.math.abs(xDiff) > 0.35) {
            val snappedLocal = Vector3f(railLocalCenterX, relPos.y, relPos.z).rotate(rot)
            cart.teleport(Location(cart.world, p.x + snappedLocal.x, loc.y, p.z + snappedLocal.z, loc.yaw, loc.pitch))
          }

          if (rail.isPowered) {
            if (kotlin.math.abs(localVz) < 0.05) {
              val yaw = localRiderYaw ?: ((loc.yaw % 360 + 360) % 360)
              localVz = if (yaw in 90.0..270.0) 0.4 else -0.4
            } else {
              localVz = if (localVz > 0) 0.4 else -0.4
            }
          } else {
            if (rider != null && kotlin.math.abs(localVz) < 0.15) {
              val yaw = (localRiderYaw ?: 0f) % 360
              localVz = if (yaw in 90.0..270.0) 0.2 else -0.2
            } else if (kotlin.math.abs(localVz) > 0.005) {
              localVz *= 0.995
            }
          }
        }

        org.bukkit.block.data.Rail.Shape.EAST_WEST -> {
          val zDiff = relPos.z - railLocalCenterZ
          localVz = (-zDiff * 0.25).coerceIn(-0.1, 0.1)
          if (kotlin.math.abs(zDiff) > 0.35) {
            val snappedLocal = Vector3f(relPos.x, relPos.y, railLocalCenterZ).rotate(rot)
            cart.teleport(Location(cart.world, p.x + snappedLocal.x, loc.y, p.z + snappedLocal.z, loc.yaw, loc.pitch))
          }

          if (rail.isPowered) {
            if (kotlin.math.abs(localVz) < 0.05) {
              val yaw = localRiderYaw ?: ((loc.yaw % 360 + 360) % 360)
              localVx = if (yaw in 0.0..180.0) -0.4 else 0.4
            } else {
              localVx = if (localVx > 0) 0.4 else -0.4
            }
          } else {
            if (rider != null && kotlin.math.abs(localVx) < 0.15) {
              val yaw = (localRiderYaw ?: 0f) % 360
              localVx = if (yaw in 0.0..180.0) -0.2 else 0.2
            } else if (kotlin.math.abs(localVx) > 0.005) {
              localVx *= 0.995
            }
          }
        }

        org.bukkit.block.data.Rail.Shape.SOUTH_EAST -> {
          if (localVz > 0.05 && kotlin.math.abs(localVx) < 0.05) {
            localVx = localVz
            localVz = 0.0
          } else if (localVx < -0.05 && kotlin.math.abs(localVz) < 0.05) {
            localVz = -localVx
            localVx = 0.0
          }
          val spd = sqrt(localVx * localVx + localVz * localVz)
          if (spd > 0.005) {
            val f = if (rail.isPowered) 0.4 / spd else 0.995
            localVx *= f
            localVz *= f
          }
        }

        org.bukkit.block.data.Rail.Shape.SOUTH_WEST -> {
          if (localVz > 0.05 && kotlin.math.abs(localVx) < 0.05) {
            localVx = -localVz
            localVz = 0.0
          } else if (localVx > 0.05 && kotlin.math.abs(localVz) < 0.05) {
            localVz = localVx
            localVx = 0.0
          }
          val spd = sqrt(localVx * localVx + localVz * localVz)
          if (spd > 0.005) {
            val f = if (rail.isPowered) 0.4 / spd else 0.995
            localVx *= f
            localVz *= f
          }
        }

        org.bukkit.block.data.Rail.Shape.NORTH_EAST -> {
          if (localVz < -0.05 && kotlin.math.abs(localVx) < 0.05) {
            localVx = -localVz
            localVz = 0.0
          } else if (localVx < -0.05 && kotlin.math.abs(localVz) < 0.05) {
            localVz = localVx
            localVx = 0.0
          }
          val spd = sqrt(localVx * localVx + localVz * localVz)
          if (spd > 0.005) {
            val f = if (rail.isPowered) 0.4 / spd else 0.995
            localVx *= f
            localVz *= f
          }
        }

        org.bukkit.block.data.Rail.Shape.NORTH_WEST -> {
          if (localVz < -0.05 && kotlin.math.abs(localVx) < 0.05) {
            localVx = localVz
            localVz = 0.0
          } else if (localVx > 0.05 && kotlin.math.abs(localVz) < 0.05) {
            localVz = -localVx
            localVx = 0.0
          }
          val spd = sqrt(localVx * localVx + localVz * localVz)
          if (spd > 0.005) {
            val f = if (rail.isPowered) 0.4 / spd else 0.995
            localVx *= f
            localVz *= f
          }
        }

        else -> {
          val spd = sqrt(localVx * localVx + localVz * localVz)
          if (rail.isPowered) {
            if (spd > 0.01) {
              val f = 0.4 / spd
              localVx *= f
              localVz *= f
            }
          } else if (spd > 0.005) {
            localVx *= 0.995
            localVz *= 0.995
          }
        }
      }

      val newWorldVel = Vector3f(localVx.toFloat(), vy.toFloat(), localVz.toFloat()).rotate(rot)
      cart.velocity = Vector(newWorldVel.x.toDouble(), newWorldVel.y.toDouble(), newWorldVel.z.toDouble())
      return
    }

    when (shape) {
      org.bukkit.block.data.Rail.Shape.NORTH_SOUTH -> {
        val centerX = rail.railCenterX
        val xDiff = loc.x - centerX
        val vx = (-xDiff * 0.25).coerceIn(-0.1, 0.1)
        if (kotlin.math.abs(xDiff) > 0.35) {
          cart.teleport(Location(cart.world, centerX, loc.y, loc.z, loc.yaw, loc.pitch))
        }

        var vz = vel.z
        if (rail.isPowered) {
          if (kotlin.math.abs(vz) < 0.05) {
            val yaw = riderYaw ?: ((loc.yaw % 360 + 360) % 360)
            vz = if (yaw in 90.0..270.0) 0.4 else -0.4
          } else {
            vz = if (vz > 0) 0.4 else -0.4
          }
        } else {
          if (rider != null && kotlin.math.abs(vz) < 0.15) {
            val yaw = (riderYaw!! % 360 + 360) % 360
            vz = if (yaw in 90.0..270.0) 0.2 else -0.2
          } else if (kotlin.math.abs(vz) > 0.005) {
            vz *= 0.995
          }
        }
        cart.velocity = Vector(vx, vy, vz)
      }

      org.bukkit.block.data.Rail.Shape.EAST_WEST -> {
        val centerZ = rail.railCenterZ
        val zDiff = loc.z - centerZ
        val vz = (-zDiff * 0.25).coerceIn(-0.1, 0.1)
        if (kotlin.math.abs(zDiff) > 0.35) {
          cart.teleport(Location(cart.world, loc.x, loc.y, centerZ, loc.yaw, loc.pitch))
        }

        var vx = vel.x
        if (rail.isPowered) {
          if (kotlin.math.abs(vx) < 0.05) {
            val yaw = riderYaw ?: ((loc.yaw % 360 + 360) % 360)
            vx = if (yaw in 0.0..180.0) -0.4 else 0.4
          } else {
            vx = if (vx > 0) 0.4 else -0.4
          }
        } else {
          if (rider != null && kotlin.math.abs(vx) < 0.15) {
            val yaw = (riderYaw!! % 360 + 360) % 360
            vx = if (yaw in 0.0..180.0) -0.2 else 0.2
          } else if (kotlin.math.abs(vx) > 0.005) {
            vx *= 0.995
          }
        }
        cart.velocity = Vector(vx, vy, vz)
      }

      org.bukkit.block.data.Rail.Shape.SOUTH_EAST -> {
        var vx = vel.x
        var vz = vel.z
        if (vz > 0.05 && kotlin.math.abs(vx) < 0.05) {
          vx = vz
          vz = 0.0
        } else if (vx < -0.05 && kotlin.math.abs(vz) < 0.05) {
          vz = -vx
          vx = 0.0
        }
        val spd = sqrt(vx * vx + vz * vz)
        if (spd > 0.005) {
          val f = if (rail.isPowered) 0.4 / spd else 0.995
          vx *= f
          vz *= f
        }
        cart.velocity = Vector(vx, vy, vz)
      }

      org.bukkit.block.data.Rail.Shape.SOUTH_WEST -> {
        var vx = vel.x
        var vz = vel.z
        if (vz > 0.05 && kotlin.math.abs(vx) < 0.05) {
          vx = -vz
          vz = 0.0
        } else if (vx > 0.05 && kotlin.math.abs(vz) < 0.05) {
          vz = vx
          vx = 0.0
        }
        val spd = sqrt(vx * vx + vz * vz)
        if (spd > 0.005) {
          val f = if (rail.isPowered) 0.4 / spd else 0.995
          vx *= f
          vz *= f
        }
        cart.velocity = Vector(vx, vy, vz)
      }

      org.bukkit.block.data.Rail.Shape.NORTH_EAST -> {
        var vx = vel.x
        var vz = vel.z
        if (vz < -0.05 && kotlin.math.abs(vx) < 0.05) {
          vx = -vz
          vz = 0.0
        } else if (vx < -0.05 && kotlin.math.abs(vz) < 0.05) {
          vz = vx
          vx = 0.0
        }
        val spd = sqrt(vx * vx + vz * vz)
        if (spd > 0.005) {
          val f = if (rail.isPowered) 0.4 / spd else 0.995
          vx *= f
          vz *= f
        }
        cart.velocity = Vector(vx, vy, vz)
      }

      org.bukkit.block.data.Rail.Shape.NORTH_WEST -> {
        var vx = vel.x
        var vz = vel.z
        if (vz < -0.05 && kotlin.math.abs(vx) < 0.05) {
          vx = vz
          vz = 0.0
        } else if (vx > 0.05 && kotlin.math.abs(vz) < 0.05) {
          vz = -vx
          vx = 0.0
        }
        val spd = sqrt(vx * vx + vz * vz)
        if (spd > 0.005) {
          val f = if (rail.isPowered) 0.4 / spd else 0.995
          vx *= f
          vz *= f
        }
        cart.velocity = Vector(vx, vy, vz)
      }

      else -> {
        var newVel = vel
        if (rail.isPowered) {
          val spd = vel.length()
          if (spd > 0.01) {
            newVel = vel.normalize().multiply(0.4)
          }
        } else if (vel.length() > 0.005) {
          newVel = vel.multiply(0.995)
        }
        cart.velocity = Vector(newVel.x, vy, newVel.z)
      }
    }
  }

  /**
   * トロッコが通電中のパワードレール（バニラレールまたはからくりレール）上にあるかを判定
   */
  fun isCartOnPoweredRail(cart: Minecart): Boolean {
    val loc = cart.location
    val bAt = loc.block
    val bBelow = loc.clone().add(0.0, -0.4, 0.0).block
    val bSlightBelow = loc.clone().add(0.0, -0.1, 0.0).block
    fun isPow(b: org.bukkit.block.Block): Boolean {
      val data = b.blockData
      return data is org.bukkit.block.data.type.RedstoneRail && data.isPowered
    }
    if (isPow(bAt) || isPow(bBelow) || isPow(bSlightBelow)) return true

    val kRail = findKarakuriRailNear(loc)
    return kRail != null && kRail.isPowered
  }

  fun getCartForwardDirection(cart: Minecart): Vector {
    val yaw = (cart.location.yaw % 360 + 360) % 360
    val rad = Math.toRadians(yaw.toDouble())
    return Vector(-kotlin.math.sin(rad), 0.0, kotlin.math.cos(rad)).normalize()
  }

  fun alignVectorToRail(loc: Location, dir: Vector, speed: Double): Vector {
    val railData = getRailDataAt(loc)
    val shape = railData?.shape ?: org.bukkit.block.data.Rail.Shape.NORTH_SOUTH
    return when (shape) {
      org.bukkit.block.data.Rail.Shape.NORTH_SOUTH -> {
        val vz = if (dir.z >= 0) speed else -speed
        Vector(0.0, 0.0, vz)
      }
      org.bukkit.block.data.Rail.Shape.EAST_WEST -> {
        val vx = if (dir.x >= 0) speed else -speed
        Vector(vx, 0.0, 0.0)
      }
      else -> {
        if (dir.lengthSquared() > 0.001) dir.normalize().multiply(speed) else Vector(speed, 0.0, 0.0)
      }
    }
  }

  /**
   * プレイヤーによるトロッコ手押し（素手 + Shift + 左クリック）
   * 視線の方向へレール軸に沿って推進力を与える！
   */
  fun pushCartByPlayer(player: Player, cart: Minecart) {
    if (!cart.isValid || cart.isDead) return
    val train = getTrainForCart(cart)

    val lookDir = player.eyeLocation.direction.setY(0.0).normalize()

    if (train != null && train.carts.size >= 2) {
      val leadCart = train.carts.first()
      val pushVec = alignVectorToRail(leadCart.location, lookDir, 0.4)
      leadCart.velocity = pushVec

      for (i in 1 until train.carts.size) {
        train.carts[i].velocity = pushVec
      }
      initTrainPath(train)
    } else {
      val pushVec = alignVectorToRail(cart.location, lookDir, 0.4)
      cart.velocity = pushVec
    }

    cart.world.spawnParticle(Particle.CRIT, cart.location.clone().add(0.0, 0.5, 0.0), 10, 0.2, 0.2, 0.2, 0.05)
    cart.world.playSound(cart.location, Sound.ENTITY_MINECART_RIDING, 0.7f, 1.3f)
  }

  fun cleanupAll() {
    playerPushMode.clear()
    for (cart in cartsOnKarakuriRail) {
      if (cart.isValid) cart.setGravity(true)
    }
    cartsOnKarakuriRail.clear()

    for (link in links.values) {
      link.chainDisplay?.let { if (it.isValid) it.remove() }
      link.interaction?.let { if (it.isValid) it.remove() }
    }
    links.clear()
    trains.clear()
    selectingPlayers.clear()
  }
}
