package com.alytanta.oneblock.market

import com.alytanta.oneblock.locale.Lang
import org.bukkit.Material
import org.bukkit.entity.Player

private data class Option(val icon:Material,val title:Lang,val args:Array<Pair<String,Any>>,val choose:(Player)->Unit)
private fun MarketController.options(player:Player,view:String,title:Lang,choices:List<Option>,back:(Player)->Unit,page:Int=0) {
    val holder=menu(player,title,view=view)?:return
    val grid=holder.grid(listOf(10,12,14,16,22))
    val current=page.coerceIn(0,(choices.size-1).coerceAtLeast(0)/grid.size)
    choices.drop(current*grid.size).take(grid.size).forEachIndexed {i,choice->button(player,holder,grid[i],choice.icon,choice.title,*choice.args,action=choice.choose)}
    navigation(player,holder,back)
    if(current>0)button(player,holder,46,Material.ARROW,Lang.MENU_PREVIOUS) {options(it,view,title,choices,back,current-1)}
    if((current+1)*grid.size<choices.size)button(player,holder,52,Material.ARROW,Lang.MENU_NEXT) {options(it,view,title,choices,back,current+1)}
    show(player,holder)
}
internal fun MarketController.auctionSortOptions(player:Player,selected:AuctionSort,back:(Player)->Unit,choose:(Player,AuctionSort)->Unit) {
    val choices=listOf(AuctionSort.HIGHEST,AuctionSort.LOWEST,AuctionSort.ENDING,AuctionSort.MOST_BIDS,AuctionSort.NEWEST).map {choice->
        Option(if(choice==selected)Material.LIME_DYE else Material.HOPPER,Lang.AUCTION_SORT_OPTION,
            arrayOf("selected" to if(choice==selected)"✔ " else "","sort" to locales.text(player,Lang.valueOf("AUCTION_SORT_${choice.name}")))) {choose(it,choice)}
    }
    options(player,"sort",Lang.AUCTION_SORT_MENU,choices,back)
}
internal fun MarketController.auctionRarityOptions(player:Player,selected:String?,back:(Player)->Unit,choose:(Player,String?)->Unit) {
    val choices=(listOf<String?>(null)+AuctionSettings.RARITIES).map {choice->
        Option(if(choice==selected)Material.LIME_DYE else Material.ENDER_EYE,Lang.AUCTION_RARITY_OPTION,
            arrayOf("selected" to if(choice==selected)"✔ " else "","rarity" to locales.text(player,if(choice==null)Lang.AUCTION_ALL_RARITIES else Lang.valueOf("AUCTION_RARITY_$choice")))) {choose(it,choice)}
    }
    options(player,"rarity",Lang.AUCTION_RARITY_MENU,choices,back)
}
internal fun MarketController.auctionDurationOptions(player:Player,selected:Long,back:(Player)->Unit,choose:(Player,Long)->Unit) {
    val colors=listOf(Material.RED_TERRACOTTA,Material.PINK_TERRACOTTA,Material.ORANGE_TERRACOTTA,Material.YELLOW_TERRACOTTA,Material.WHITE_TERRACOTTA)
    val choices=settings.auction.durations.mapIndexed {i,hours->Option(colors[i%colors.size],Lang.AUCTION_DURATION_OPTION,
        arrayOf("selected" to if(hours==selected)"✔ " else "","hours" to hours)) {choose(it,hours)} }
    options(player,"duration",Lang.AUCTION_DURATION_MENU,choices,back)
}
