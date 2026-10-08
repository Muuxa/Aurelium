// AURELIUM 导出示例（KubeJS server_scripts）
// 放到 kubejs/server_scripts/ 下，重启或 /reload 后生效。
//
// 作用：把主手/副手持有的宝匣内容导出成三个文件
//       （① 注册脚本 data.js  ② 配套配方 recipe.js  ③ 人读清单 txt），
// 文件写到 <存档>/data/Aurelium/export/ 下。
//
// 用法一：蹲下 + 右键手持宝匣触发导出（文件名叫 my_vault）
PlayerEvents.tick(event => {
  const p = event.player
  if (!p.isCrouching()) return
  // 只处理有宝匣的手，避免每 tick 重复写文件
  const id = p.offhandItem.id === 'aurelium:star_vault'
    ? p.offhandItem.id
    : (p.mainHandItem.id === 'aurelium:star_vault' ? p.mainHandItem.id : null)
  if (id === null) return
  // 简单去抖：只在刚好按下蹲的那一 tick 触发
  if (p.getPersistentData().getBoolean('aurelium_export_done')) return
  p.getPersistentData().putBoolean('aurelium_export_done', true)
  try {
    const paths = AureliumExport.held(p, 'my_vault')
    // paths: [data(注册脚本), recipe(配方), analysis(人读清单)]
    console.info('[AURELIUM] 已导出: ' + paths[0] + ' , ' + paths[1] + ' , ' + paths[2])
  } catch (err) {
    console.error('[AURELIUM] 导出失败: ' + err)
  }
})

// 松开蹲下时复位去抖标记
PlayerEvents.tick(event => {
  const p = event.player
  if (!p.isCrouching()) {
    p.getPersistentData().putBoolean('aurelium_export_done', false)
  }
})

// 用法二：脚本里查看某个已注册包的条目
// ServerEvents.commandRegistry(event => {
//   const { commands: Commands } = event
//   event.register(
//     Commands.literal('dump_pack')
//       .then(Commands.argument('id', event.arguments.STRING.create(event))
//         .executes(ctx => {
//           console.info(AureliumCell.describePack(event.arguments.STRING.getResult(ctx, 'id')))
//           return 1
//         }))
//   )
// })
