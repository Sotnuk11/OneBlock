package com.alytanta.oneblock.menu

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.SkullMeta
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ViewerSkinTest {
    @Test fun `menu heads use existing skin properties and never complete a missing offline skin`() {
        for(known in listOf(false,true)) {
            var assigned:Any?="unchanged"
            val properties=if(known)setOf(ProfileProperty("textures","known-texture-data","signature")) else emptySet()
            val profile=MenuFixture.stub(PlayerProfile::class.java) {method,_->when(method) {"getProperties"->properties;else->error("Profile lookup/completion is forbidden: $method")}}
            val player=MenuFixture.stub(Player::class.java) {method,_->if(method=="getPlayerProfile")profile else error(method)}
            val meta=MenuFixture.stub(SkullMeta::class.java) {method,args->if(method=="setOwnerProfile") {assigned=args!![0];null} else error(method)}
            GuiItems.viewerSkin(meta,player)
            if(known)assertSame(profile,assigned) else assertNull(assigned)
        }
    }
}
