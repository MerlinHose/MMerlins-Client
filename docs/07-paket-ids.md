# Paket-ID-Tabellen (Minecraft 26.3, Protokoll 777)

> Automatisch erzeugt von `analysis/tools/PacketTableExtractor.java` aus den Loom-Quellen.
> Die ID ist die Registrierungsreihenfolge im jeweiligen `*_TEMPLATE` (VarInt-kodiert, siehe `IdDispatchCodec.encode/decode`).
> `withBundlePacket` registriert nur das Delimiter-Paket (`ProtocolInfoBuilder.withBundlePacket`).
>
> **Laufzeit-verifiziert** (docs/10-laufzeittest.md): alle IDs stimmen mit dem offiziellen Report
> `net.minecraft.data.Main --reports` → `packets.json` überein (260/260: 254 namensgleich + 6 nur namensverschieden, 0 ID-Abweichungen).
> Achtung: Die Spalte *PacketType* zeigt den Java-Konstantennamen, nicht immer die Ressourcen-ID auf dem Draht/im Log:
> `CLIENT_INTENTION` = `minecraft:intention`, `CLIENTBOUND_BUNDLE` = `minecraft:bundle_delimiter`,
> `CLIENTBOUND_DEBUG_*_VALUE`/`DEBUG_EVENT` = `minecraft:debug/*_value` bzw. `minecraft:debug/event`.

## handshake / serverbound

Quelle: `net/minecraft/network/protocol/handshake/HandshakeProtocols.SERVERBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `HandshakePacketTypes.CLIENT_INTENTION` | `ClientIntentionPacket` |

1 Pakete.

## status / serverbound

Quelle: `net/minecraft/network/protocol/status/StatusProtocols.SERVERBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `StatusPacketTypes.SERVERBOUND_STATUS_REQUEST` | `ServerboundStatusRequestPacket` |
| 1 | 0x01 | `PingPacketTypes.SERVERBOUND_PING_REQUEST` | `ServerboundPingRequestPacket` |

2 Pakete.

## status / clientbound

Quelle: `net/minecraft/network/protocol/status/StatusProtocols.CLIENTBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `StatusPacketTypes.CLIENTBOUND_STATUS_RESPONSE` | `ClientboundStatusResponsePacket` |
| 1 | 0x01 | `PingPacketTypes.CLIENTBOUND_PONG_RESPONSE` | `ClientboundPongResponsePacket` |

2 Pakete.

## login / serverbound

Quelle: `net/minecraft/network/protocol/login/LoginProtocols.SERVERBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `LoginPacketTypes.SERVERBOUND_HELLO` | `ServerboundHelloPacket` |
| 1 | 0x01 | `LoginPacketTypes.SERVERBOUND_KEY` | `ServerboundKeyPacket` |
| 2 | 0x02 | `LoginPacketTypes.SERVERBOUND_CUSTOM_QUERY_ANSWER` | `ServerboundCustomQueryAnswerPacket` |
| 3 | 0x03 | `LoginPacketTypes.SERVERBOUND_LOGIN_ACKNOWLEDGED` | `ServerboundLoginAcknowledgedPacket` |
| 4 | 0x04 | `CookiePacketTypes.SERVERBOUND_COOKIE_RESPONSE` | `ServerboundCookieResponsePacket` |

5 Pakete.

## login / clientbound

Quelle: `net/minecraft/network/protocol/login/LoginProtocols.CLIENTBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `LoginPacketTypes.CLIENTBOUND_LOGIN_DISCONNECT` | `ClientboundLoginDisconnectPacket` |
| 1 | 0x01 | `LoginPacketTypes.CLIENTBOUND_HELLO` | `ClientboundHelloPacket` |
| 2 | 0x02 | `LoginPacketTypes.CLIENTBOUND_LOGIN_FINISHED` | `ClientboundLoginFinishedPacket` |
| 3 | 0x03 | `LoginPacketTypes.CLIENTBOUND_LOGIN_COMPRESSION` | `ClientboundLoginCompressionPacket` |
| 4 | 0x04 | `LoginPacketTypes.CLIENTBOUND_CUSTOM_QUERY` | `ClientboundCustomQueryPacket` |
| 5 | 0x05 | `CookiePacketTypes.CLIENTBOUND_COOKIE_REQUEST` | `ClientboundCookieRequestPacket` |

6 Pakete.

## configuration / serverbound

Quelle: `net/minecraft/network/protocol/configuration/ConfigurationProtocols.SERVERBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `CommonPacketTypes.SERVERBOUND_CLIENT_INFORMATION` | `ServerboundClientInformationPacket` |
| 1 | 0x01 | `CookiePacketTypes.SERVERBOUND_COOKIE_RESPONSE` | `ServerboundCookieResponsePacket` |
| 2 | 0x02 | `CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD` | `ServerboundCustomPayloadPacket` |
| 3 | 0x03 | `ConfigurationPacketTypes.SERVERBOUND_FINISH_CONFIGURATION` | `ServerboundFinishConfigurationPacket` |
| 4 | 0x04 | `CommonPacketTypes.SERVERBOUND_KEEP_ALIVE` | `ServerboundKeepAlivePacket` |
| 5 | 0x05 | `CommonPacketTypes.SERVERBOUND_PONG` | `ServerboundPongPacket` |
| 6 | 0x06 | `CommonPacketTypes.SERVERBOUND_RESOURCE_PACK` | `ServerboundResourcePackPacket` |
| 7 | 0x07 | `ConfigurationPacketTypes.SERVERBOUND_SELECT_KNOWN_PACKS` | `ServerboundSelectKnownPacks` |
| 8 | 0x08 | `CommonPacketTypes.SERVERBOUND_CUSTOM_CLICK_ACTION` | `ServerboundCustomClickActionPacket` |
| 9 | 0x09 | `ConfigurationPacketTypes.SERVERBOUND_ACCEPT_CODE_OF_CONDUCT` | `ServerboundAcceptCodeOfConductPacket` |

10 Pakete.

## configuration / clientbound

Quelle: `net/minecraft/network/protocol/configuration/ConfigurationProtocols.CLIENTBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `CookiePacketTypes.CLIENTBOUND_COOKIE_REQUEST` | `ClientboundCookieRequestPacket` |
| 1 | 0x01 | `CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD` | `ClientboundCustomPayloadPacket.CONFIG_STREAM_CODEC` |
| 2 | 0x02 | `CommonPacketTypes.CLIENTBOUND_DISCONNECT` | `ClientboundDisconnectPacket` |
| 3 | 0x03 | `ConfigurationPacketTypes.CLIENTBOUND_FINISH_CONFIGURATION` | `ClientboundFinishConfigurationPacket` |
| 4 | 0x04 | `CommonPacketTypes.CLIENTBOUND_KEEP_ALIVE` | `ClientboundKeepAlivePacket` |
| 5 | 0x05 | `CommonPacketTypes.CLIENTBOUND_PING` | `ClientboundPingPacket` |
| 6 | 0x06 | `ConfigurationPacketTypes.CLIENTBOUND_RESET_CHAT` | `ClientboundResetChatPacket` |
| 7 | 0x07 | `ConfigurationPacketTypes.CLIENTBOUND_REGISTRY_DATA` | `ClientboundRegistryDataPacket` |
| 8 | 0x08 | `CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_POP` | `ClientboundResourcePackPopPacket` |
| 9 | 0x09 | `CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_PUSH` | `ClientboundResourcePackPushPacket` |
| 10 | 0x0A | `CommonPacketTypes.CLIENTBOUND_POST_EFFECTS` | `ClientboundPostEffectsPacket` |
| 11 | 0x0B | `CommonPacketTypes.CLIENTBOUND_STORE_COOKIE` | `ClientboundStoreCookiePacket` |
| 12 | 0x0C | `CommonPacketTypes.CLIENTBOUND_TRANSFER` | `ClientboundTransferPacket` |
| 13 | 0x0D | `ConfigurationPacketTypes.CLIENTBOUND_UPDATE_ENABLED_FEATURES` | `ClientboundUpdateEnabledFeaturesPacket` |
| 14 | 0x0E | `CommonPacketTypes.CLIENTBOUND_UPDATE_TAGS` | `ClientboundUpdateTagsPacket` |
| 15 | 0x0F | `ConfigurationPacketTypes.CLIENTBOUND_SELECT_KNOWN_PACKS` | `ClientboundSelectKnownPacks` |
| 16 | 0x10 | `CommonPacketTypes.CLIENTBOUND_CUSTOM_REPORT_DETAILS` | `ClientboundCustomReportDetailsPacket` |
| 17 | 0x11 | `CommonPacketTypes.CLIENTBOUND_SERVER_LINKS` | `ClientboundServerLinksPacket` |
| 18 | 0x12 | `CommonPacketTypes.CLIENTBOUND_CLEAR_DIALOG` | `ClientboundClearDialogPacket` |
| 19 | 0x13 | `CommonPacketTypes.CLIENTBOUND_SHOW_DIALOG` | `ClientboundShowDialogPacket.CONTEXT_FREE_STREAM_CODEC` |
| 20 | 0x14 | `ConfigurationPacketTypes.CLIENTBOUND_CODE_OF_CONDUCT` | `ClientboundCodeOfConductPacket` |

21 Pakete.

## play / serverbound

Quelle: `net/minecraft/network/protocol/game/GameProtocols.SERVERBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `GamePacketTypes.SERVERBOUND_ACCEPT_TELEPORTATION` | `ServerboundAcceptTeleportationPacket` |
| 1 | 0x01 | `GamePacketTypes.SERVERBOUND_ATTACK` | `ServerboundAttackPacket` |
| 2 | 0x02 | `GamePacketTypes.SERVERBOUND_BLOCK_ENTITY_TAG_QUERY` | `ServerboundBlockEntityTagQueryPacket` |
| 3 | 0x03 | `GamePacketTypes.SERVERBOUND_BUNDLE_ITEM_SELECTED` | `ServerboundSelectBundleItemPacket` |
| 4 | 0x04 | `GamePacketTypes.SERVERBOUND_CHANGE_DIFFICULTY` | `ServerboundChangeDifficultyPacket` |
| 5 | 0x05 | `GamePacketTypes.SERVERBOUND_CHANGE_GAME_MODE` | `ServerboundChangeGameModePacket` |
| 6 | 0x06 | `GamePacketTypes.SERVERBOUND_CHAT_ACK` | `ServerboundChatAckPacket` |
| 7 | 0x07 | `GamePacketTypes.SERVERBOUND_CHAT_COMMAND` | `ServerboundChatCommandPacket` |
| 8 | 0x08 | `GamePacketTypes.SERVERBOUND_CHAT_COMMAND_SIGNED` | `ServerboundChatCommandSignedPacket` |
| 9 | 0x09 | `GamePacketTypes.SERVERBOUND_CHAT` | `ServerboundChatPacket` |
| 10 | 0x0A | `GamePacketTypes.SERVERBOUND_CHAT_SESSION_UPDATE` | `ServerboundChatSessionUpdatePacket` |
| 11 | 0x0B | `GamePacketTypes.SERVERBOUND_CHUNK_BATCH_RECEIVED` | `ServerboundChunkBatchReceivedPacket` |
| 12 | 0x0C | `GamePacketTypes.SERVERBOUND_CLIENT_COMMAND` | `ServerboundClientCommandPacket` |
| 13 | 0x0D | `GamePacketTypes.SERVERBOUND_CLIENT_TICK_END` | `ServerboundClientTickEndPacket` |
| 14 | 0x0E | `CommonPacketTypes.SERVERBOUND_CLIENT_INFORMATION` | `ServerboundClientInformationPacket` |
| 15 | 0x0F | `GamePacketTypes.SERVERBOUND_COMMAND_SUGGESTION` | `ServerboundCommandSuggestionPacket` |
| 16 | 0x10 | `GamePacketTypes.SERVERBOUND_CONFIGURATION_ACKNOWLEDGED` | `ServerboundConfigurationAcknowledgedPacket` |
| 17 | 0x11 | `GamePacketTypes.SERVERBOUND_CONTAINER_BUTTON_CLICK` | `ServerboundContainerButtonClickPacket` |
| 18 | 0x12 | `GamePacketTypes.SERVERBOUND_CONTAINER_CLICK` | `ServerboundContainerClickPacket` |
| 19 | 0x13 | `GamePacketTypes.SERVERBOUND_CONTAINER_CLOSE` | `ServerboundContainerClosePacket` |
| 20 | 0x14 | `GamePacketTypes.SERVERBOUND_CONTAINER_SLOT_STATE_CHANGED` | `ServerboundContainerSlotStateChangedPacket` |
| 21 | 0x15 | `CookiePacketTypes.SERVERBOUND_COOKIE_RESPONSE` | `ServerboundCookieResponsePacket` |
| 22 | 0x16 | `CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD` | `ServerboundCustomPayloadPacket` |
| 23 | 0x17 | `GamePacketTypes.SERVERBOUND_DEBUG_SUBSCRIPTION_REQUEST` | `ServerboundDebugSubscriptionRequestPacket` |
| 24 | 0x18 | `GamePacketTypes.SERVERBOUND_EDIT_BOOK` | `ServerboundEditBookPacket` |
| 25 | 0x19 | `GamePacketTypes.SERVERBOUND_ENTITY_TAG_QUERY` | `ServerboundEntityTagQueryPacket` |
| 26 | 0x1A | `GamePacketTypes.SERVERBOUND_INTERACT` | `ServerboundInteractPacket` |
| 27 | 0x1B | `GamePacketTypes.SERVERBOUND_JIGSAW_GENERATE` | `ServerboundJigsawGeneratePacket` |
| 28 | 0x1C | `CommonPacketTypes.SERVERBOUND_KEEP_ALIVE` | `ServerboundKeepAlivePacket` |
| 29 | 0x1D | `GamePacketTypes.SERVERBOUND_LOCK_DIFFICULTY` | `ServerboundLockDifficultyPacket` |
| 30 | 0x1E | `GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS` | `ServerboundMovePlayerPacket.Pos` |
| 31 | 0x1F | `GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS_ROT` | `ServerboundMovePlayerPacket.PosRot` |
| 32 | 0x20 | `GamePacketTypes.SERVERBOUND_MOVE_PLAYER_ROT` | `ServerboundMovePlayerPacket.Rot` |
| 33 | 0x21 | `GamePacketTypes.SERVERBOUND_MOVE_PLAYER_STATUS_ONLY` | `ServerboundMovePlayerPacket.StatusOnly` |
| 34 | 0x22 | `GamePacketTypes.SERVERBOUND_MOVE_VEHICLE` | `ServerboundMoveVehiclePacket` |
| 35 | 0x23 | `GamePacketTypes.SERVERBOUND_PADDLE_BOAT` | `ServerboundPaddleBoatPacket` |
| 36 | 0x24 | `GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_BLOCK` | `ServerboundPickItemFromBlockPacket` |
| 37 | 0x25 | `GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_ENTITY` | `ServerboundPickItemFromEntityPacket` |
| 38 | 0x26 | `PingPacketTypes.SERVERBOUND_PING_REQUEST` | `ServerboundPingRequestPacket` |
| 39 | 0x27 | `GamePacketTypes.SERVERBOUND_PLACE_RECIPE` | `ServerboundPlaceRecipePacket` |
| 40 | 0x28 | `GamePacketTypes.SERVERBOUND_PLAYER_ABILITIES` | `ServerboundPlayerAbilitiesPacket` |
| 41 | 0x29 | `GamePacketTypes.SERVERBOUND_PLAYER_ACTION` | `ServerboundPlayerActionPacket` |
| 42 | 0x2A | `GamePacketTypes.SERVERBOUND_PLAYER_COMMAND` | `ServerboundPlayerCommandPacket` |
| 43 | 0x2B | `GamePacketTypes.SERVERBOUND_PLAYER_INPUT` | `ServerboundPlayerInputPacket` |
| 44 | 0x2C | `GamePacketTypes.SERVERBOUND_PLAYER_LOADED` | `ServerboundPlayerLoadedPacket` |
| 45 | 0x2D | `CommonPacketTypes.SERVERBOUND_PONG` | `ServerboundPongPacket` |
| 46 | 0x2E | `GamePacketTypes.SERVERBOUND_PUNCH` | `ServerboundPunchPacket` |
| 47 | 0x2F | `GamePacketTypes.SERVERBOUND_RECIPE_BOOK_CHANGE_SETTINGS` | `ServerboundRecipeBookChangeSettingsPacket` |
| 48 | 0x30 | `GamePacketTypes.SERVERBOUND_RECIPE_BOOK_SEEN_RECIPE` | `ServerboundRecipeBookSeenRecipePacket` |
| 49 | 0x31 | `GamePacketTypes.SERVERBOUND_RENAME_ITEM` | `ServerboundRenameItemPacket` |
| 50 | 0x32 | `CommonPacketTypes.SERVERBOUND_RESOURCE_PACK` | `ServerboundResourcePackPacket` |
| 51 | 0x33 | `GamePacketTypes.SERVERBOUND_SEEN_ADVANCEMENTS` | `ServerboundSeenAdvancementsPacket` |
| 52 | 0x34 | `GamePacketTypes.SERVERBOUND_SELECT_TRADE` | `ServerboundSelectTradePacket` |
| 53 | 0x35 | `GamePacketTypes.SERVERBOUND_SET_BEACON` | `ServerboundSetBeaconPacket` |
| 54 | 0x36 | `GamePacketTypes.SERVERBOUND_SET_CARRIED_ITEM` | `ServerboundSetCarriedItemPacket` |
| 55 | 0x37 | `GamePacketTypes.SERVERBOUND_SET_COMMAND_BLOCK` | `ServerboundSetCommandBlockPacket` |
| 56 | 0x38 | `GamePacketTypes.SERVERBOUND_SET_COMMAND_MINECART` | `ServerboundSetCommandMinecartPacket` |
| 57 | 0x39 | `GamePacketTypes.SERVERBOUND_SET_CREATIVE_MODE_SLOT` | `ServerboundSetCreativeModeSlotPacket` |
| 58 | 0x3A | `GamePacketTypes.SERVERBOUND_SET_GAME_RULE` | `ServerboundSetGameRulePacket` |
| 59 | 0x3B | `GamePacketTypes.SERVERBOUND_SET_JIGSAW_BLOCK` | `ServerboundSetJigsawBlockPacket` |
| 60 | 0x3C | `GamePacketTypes.SERVERBOUND_SET_STRUCTURE_BLOCK` | `ServerboundSetStructureBlockPacket` |
| 61 | 0x3D | `GamePacketTypes.SERVERBOUND_SET_TEST_BLOCK` | `ServerboundSetTestBlockPacket` |
| 62 | 0x3E | `GamePacketTypes.SERVERBOUND_SIGN_UPDATE` | `ServerboundSignUpdatePacket` |
| 63 | 0x3F | `GamePacketTypes.SERVERBOUND_SPECTATOR_ACTION` | `ServerboundSpectatorActionPacket` |
| 64 | 0x40 | `GamePacketTypes.SERVERBOUND_TELEPORT_TO_ENTITY` | `ServerboundTeleportToEntityPacket` |
| 65 | 0x41 | `GamePacketTypes.SERVERBOUND_TEST_INSTANCE_BLOCK_ACTION` | `ServerboundTestInstanceBlockActionPacket` |
| 66 | 0x42 | `GamePacketTypes.SERVERBOUND_USE_ITEM_ON` | `ServerboundUseItemOnPacket` |
| 67 | 0x43 | `GamePacketTypes.SERVERBOUND_USE_ITEM` | `ServerboundUseItemPacket` |
| 68 | 0x44 | `CommonPacketTypes.SERVERBOUND_CUSTOM_CLICK_ACTION` | `ServerboundCustomClickActionPacket` |

69 Pakete.

## play / clientbound

Quelle: `net/minecraft/network/protocol/game/GameProtocols.CLIENTBOUND_TEMPLATE`

| ID | Hex | PacketType | Klasse |
|---:|---:|---|---|
| 0 | 0x00 | `GamePacketTypes.CLIENTBOUND_BUNDLE` | `ClientboundBundleDelimiterPacket (Bundle-Delimiter)` |
| 1 | 0x01 | `GamePacketTypes.CLIENTBOUND_ADD_ENTITY` | `ClientboundAddEntityPacket` |
| 2 | 0x02 | `GamePacketTypes.CLIENTBOUND_ANIMATE` | `ClientboundAnimatePacket` |
| 3 | 0x03 | `GamePacketTypes.CLIENTBOUND_AWARD_STATS` | `ClientboundAwardStatsPacket` |
| 4 | 0x04 | `GamePacketTypes.CLIENTBOUND_BLOCK_CHANGED_ACK` | `ClientboundBlockChangedAckPacket` |
| 5 | 0x05 | `GamePacketTypes.CLIENTBOUND_BLOCK_DESTRUCTION` | `ClientboundBlockDestructionPacket` |
| 6 | 0x06 | `GamePacketTypes.CLIENTBOUND_BLOCK_ENTITY_DATA` | `ClientboundBlockEntityDataPacket` |
| 7 | 0x07 | `GamePacketTypes.CLIENTBOUND_BLOCK_EVENT` | `ClientboundBlockEventPacket` |
| 8 | 0x08 | `GamePacketTypes.CLIENTBOUND_BLOCK_UPDATE` | `ClientboundBlockUpdatePacket` |
| 9 | 0x09 | `GamePacketTypes.CLIENTBOUND_BOSS_EVENT` | `ClientboundBossEventPacket` |
| 10 | 0x0A | `GamePacketTypes.CLIENTBOUND_CHANGE_DIFFICULTY` | `ClientboundChangeDifficultyPacket` |
| 11 | 0x0B | `GamePacketTypes.CLIENTBOUND_CHUNK_BATCH_FINISHED` | `ClientboundChunkBatchFinishedPacket` |
| 12 | 0x0C | `GamePacketTypes.CLIENTBOUND_CHUNK_BATCH_START` | `ClientboundChunkBatchStartPacket` |
| 13 | 0x0D | `GamePacketTypes.CLIENTBOUND_CHUNKS_BIOMES` | `ClientboundChunksBiomesPacket` |
| 14 | 0x0E | `GamePacketTypes.CLIENTBOUND_CLEAR_TITLES` | `ClientboundClearTitlesPacket` |
| 15 | 0x0F | `GamePacketTypes.CLIENTBOUND_COMMAND_SUGGESTIONS` | `ClientboundCommandSuggestionsPacket` |
| 16 | 0x10 | `GamePacketTypes.CLIENTBOUND_COMMANDS` | `ClientboundCommandsPacket` |
| 17 | 0x11 | `GamePacketTypes.CLIENTBOUND_CONTAINER_CLOSE` | `ClientboundContainerClosePacket` |
| 18 | 0x12 | `GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT` | `ClientboundContainerSetContentPacket` |
| 19 | 0x13 | `GamePacketTypes.CLIENTBOUND_CONTAINER_SET_DATA` | `ClientboundContainerSetDataPacket` |
| 20 | 0x14 | `GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT` | `ClientboundContainerSetSlotPacket` |
| 21 | 0x15 | `CookiePacketTypes.CLIENTBOUND_COOKIE_REQUEST` | `ClientboundCookieRequestPacket` |
| 22 | 0x16 | `GamePacketTypes.CLIENTBOUND_COOLDOWN` | `ClientboundCooldownPacket` |
| 23 | 0x17 | `GamePacketTypes.CLIENTBOUND_CUSTOM_CHAT_COMPLETIONS` | `ClientboundCustomChatCompletionsPacket` |
| 24 | 0x18 | `CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD` | `ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC` |
| 25 | 0x19 | `GamePacketTypes.CLIENTBOUND_DAMAGE_EVENT` | `ClientboundDamageEventPacket` |
| 26 | 0x1A | `GamePacketTypes.CLIENTBOUND_DEBUG_BLOCK_VALUE` | `ClientboundDebugBlockValuePacket` |
| 27 | 0x1B | `GamePacketTypes.CLIENTBOUND_DEBUG_CHUNK_VALUE` | `ClientboundDebugChunkValuePacket` |
| 28 | 0x1C | `GamePacketTypes.CLIENTBOUND_DEBUG_ENTITY_VALUE` | `ClientboundDebugEntityValuePacket` |
| 29 | 0x1D | `GamePacketTypes.CLIENTBOUND_DEBUG_EVENT` | `ClientboundDebugEventPacket` |
| 30 | 0x1E | `GamePacketTypes.CLIENTBOUND_DEBUG_SAMPLE` | `ClientboundDebugSamplePacket` |
| 31 | 0x1F | `GamePacketTypes.CLIENTBOUND_DELETE_CHAT` | `ClientboundDeleteChatPacket` |
| 32 | 0x20 | `CommonPacketTypes.CLIENTBOUND_DISCONNECT` | `ClientboundDisconnectPacket` |
| 33 | 0x21 | `GamePacketTypes.CLIENTBOUND_DISGUISED_CHAT` | `ClientboundDisguisedChatPacket` |
| 34 | 0x22 | `GamePacketTypes.CLIENTBOUND_ENTITY_EVENT` | `ClientboundEntityEventPacket` |
| 35 | 0x23 | `GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC` | `ClientboundEntityPositionSyncPacket` |
| 36 | 0x24 | `GamePacketTypes.CLIENTBOUND_EXPLODE` | `ClientboundExplodePacket` |
| 37 | 0x25 | `GamePacketTypes.CLIENTBOUND_ADD_TRANSIENT_BLOCK` | `ClientboundAddTransientBlockPacket` |
| 38 | 0x26 | `GamePacketTypes.CLIENTBOUND_FORGET_LEVEL_CHUNK` | `ClientboundForgetLevelChunkPacket` |
| 39 | 0x27 | `GamePacketTypes.CLIENTBOUND_GAME_EVENT` | `ClientboundGameEventPacket` |
| 40 | 0x28 | `GamePacketTypes.CLIENTBOUND_GAME_RULE_VALUES` | `ClientboundGameRuleValuesPacket` |
| 41 | 0x29 | `GamePacketTypes.CLIENTBOUND_GAME_TEST_HIGHLIGHT_POS` | `ClientboundGameTestHighlightPosPacket` |
| 42 | 0x2A | `GamePacketTypes.CLIENTBOUND_MOUNT_SCREEN_OPEN` | `ClientboundMountScreenOpenPacket` |
| 43 | 0x2B | `GamePacketTypes.CLIENTBOUND_HURT_ANIMATION` | `ClientboundHurtAnimationPacket` |
| 44 | 0x2C | `GamePacketTypes.CLIENTBOUND_INITIALIZE_BORDER` | `ClientboundInitializeBorderPacket` |
| 45 | 0x2D | `CommonPacketTypes.CLIENTBOUND_KEEP_ALIVE` | `ClientboundKeepAlivePacket` |
| 46 | 0x2E | `GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT` | `ClientboundLevelChunkWithLightPacket` |
| 47 | 0x2F | `GamePacketTypes.CLIENTBOUND_LEVEL_EVENT` | `ClientboundLevelEventPacket` |
| 48 | 0x30 | `GamePacketTypes.CLIENTBOUND_LEVEL_PARTICLES` | `ClientboundLevelParticlesPacket` |
| 49 | 0x31 | `GamePacketTypes.CLIENTBOUND_LIGHT_UPDATE` | `ClientboundLightUpdatePacket` |
| 50 | 0x32 | `GamePacketTypes.CLIENTBOUND_LOGIN` | `ClientboundLoginPacket` |
| 51 | 0x33 | `GamePacketTypes.CLIENTBOUND_LOW_DISK_SPACE_WARNING` | `ClientboundLowDiskSpaceWarningPacket` |
| 52 | 0x34 | `GamePacketTypes.CLIENTBOUND_MAP_ITEM_DATA` | `ClientboundMapItemDataPacket` |
| 53 | 0x35 | `GamePacketTypes.CLIENTBOUND_MERCHANT_OFFERS` | `ClientboundMerchantOffersPacket` |
| 54 | 0x36 | `GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS` | `ClientboundMoveEntityPacket.Pos` |
| 55 | 0x37 | `GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT` | `ClientboundMoveEntityPacket.PosRot` |
| 56 | 0x38 | `GamePacketTypes.CLIENTBOUND_MOVE_MINECART_ALONG_TRACK` | `ClientboundMoveMinecartPacket` |
| 57 | 0x39 | `GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT` | `ClientboundMoveEntityPacket.Rot` |
| 58 | 0x3A | `GamePacketTypes.CLIENTBOUND_MOVE_VEHICLE` | `ClientboundMoveVehiclePacket` |
| 59 | 0x3B | `GamePacketTypes.CLIENTBOUND_OPEN_BOOK` | `ClientboundOpenBookPacket` |
| 60 | 0x3C | `GamePacketTypes.CLIENTBOUND_OPEN_SCREEN` | `ClientboundOpenScreenPacket` |
| 61 | 0x3D | `GamePacketTypes.CLIENTBOUND_OPEN_SIGN_EDITOR` | `ClientboundOpenSignEditorPacket` |
| 62 | 0x3E | `CommonPacketTypes.CLIENTBOUND_PING` | `ClientboundPingPacket` |
| 63 | 0x3F | `PingPacketTypes.CLIENTBOUND_PONG_RESPONSE` | `ClientboundPongResponsePacket` |
| 64 | 0x40 | `GamePacketTypes.CLIENTBOUND_PLACE_GHOST_RECIPE` | `ClientboundPlaceGhostRecipePacket` |
| 65 | 0x41 | `GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES` | `ClientboundPlayerAbilitiesPacket` |
| 66 | 0x42 | `GamePacketTypes.CLIENTBOUND_PLAYER_CHAT` | `ClientboundPlayerChatPacket` |
| 67 | 0x43 | `GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_END` | `ClientboundPlayerCombatEndPacket` |
| 68 | 0x44 | `GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_ENTER` | `ClientboundPlayerCombatEnterPacket` |
| 69 | 0x45 | `GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_KILL` | `ClientboundPlayerCombatKillPacket` |
| 70 | 0x46 | `GamePacketTypes.CLIENTBOUND_PLAYER_INFO_REMOVE` | `ClientboundPlayerInfoRemovePacket` |
| 71 | 0x47 | `GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE` | `ClientboundPlayerInfoUpdatePacket` |
| 72 | 0x48 | `GamePacketTypes.CLIENTBOUND_PLAYER_LOOK_AT` | `ClientboundPlayerLookAtPacket` |
| 73 | 0x49 | `GamePacketTypes.CLIENTBOUND_PLAYER_POSITION` | `ClientboundPlayerPositionPacket` |
| 74 | 0x4A | `GamePacketTypes.CLIENTBOUND_PLAYER_ROTATION` | `ClientboundPlayerRotationPacket` |
| 75 | 0x4B | `GamePacketTypes.CLIENTBOUND_RECIPE_BOOK_ADD` | `ClientboundRecipeBookAddPacket` |
| 76 | 0x4C | `GamePacketTypes.CLIENTBOUND_RECIPE_BOOK_REMOVE` | `ClientboundRecipeBookRemovePacket` |
| 77 | 0x4D | `GamePacketTypes.CLIENTBOUND_RECIPE_BOOK_SETTINGS` | `ClientboundRecipeBookSettingsPacket` |
| 78 | 0x4E | `GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES` | `ClientboundRemoveEntitiesPacket` |
| 79 | 0x4F | `GamePacketTypes.CLIENTBOUND_REMOVE_MOB_EFFECT` | `ClientboundRemoveMobEffectPacket` |
| 80 | 0x50 | `GamePacketTypes.CLIENTBOUND_RESET_SCORE` | `ClientboundResetScorePacket` |
| 81 | 0x51 | `CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_POP` | `ClientboundResourcePackPopPacket` |
| 82 | 0x52 | `CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_PUSH` | `ClientboundResourcePackPushPacket` |
| 83 | 0x53 | `CommonPacketTypes.CLIENTBOUND_POST_EFFECTS` | `ClientboundPostEffectsPacket` |
| 84 | 0x54 | `GamePacketTypes.CLIENTBOUND_RESPAWN` | `ClientboundRespawnPacket` |
| 85 | 0x55 | `GamePacketTypes.CLIENTBOUND_ROTATE_HEAD` | `ClientboundRotateHeadPacket` |
| 86 | 0x56 | `GamePacketTypes.CLIENTBOUND_SECTION_BLOCKS_UPDATE` | `ClientboundSectionBlocksUpdatePacket` |
| 87 | 0x57 | `GamePacketTypes.CLIENTBOUND_SELECT_ADVANCEMENTS_TAB` | `ClientboundSelectAdvancementsTabPacket` |
| 88 | 0x58 | `GamePacketTypes.CLIENTBOUND_SERVER_DATA` | `ClientboundServerDataPacket` |
| 89 | 0x59 | `GamePacketTypes.CLIENTBOUND_SET_ACTION_BAR_TEXT` | `ClientboundSetActionBarTextPacket` |
| 90 | 0x5A | `GamePacketTypes.CLIENTBOUND_SET_BORDER_CENTER` | `ClientboundSetBorderCenterPacket` |
| 91 | 0x5B | `GamePacketTypes.CLIENTBOUND_SET_BORDER_LERP_SIZE` | `ClientboundSetBorderLerpSizePacket` |
| 92 | 0x5C | `GamePacketTypes.CLIENTBOUND_SET_BORDER_SIZE` | `ClientboundSetBorderSizePacket` |
| 93 | 0x5D | `GamePacketTypes.CLIENTBOUND_SET_BORDER_WARNING_DELAY` | `ClientboundSetBorderWarningDelayPacket` |
| 94 | 0x5E | `GamePacketTypes.CLIENTBOUND_SET_BORDER_WARNING_DISTANCE` | `ClientboundSetBorderWarningDistancePacket` |
| 95 | 0x5F | `GamePacketTypes.CLIENTBOUND_SET_CAMERA` | `ClientboundSetCameraPacket` |
| 96 | 0x60 | `GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_CENTER` | `ClientboundSetChunkCacheCenterPacket` |
| 97 | 0x61 | `GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS` | `ClientboundSetChunkCacheRadiusPacket` |
| 98 | 0x62 | `GamePacketTypes.CLIENTBOUND_SET_CURSOR_ITEM` | `ClientboundSetCursorItemPacket` |
| 99 | 0x63 | `GamePacketTypes.CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION` | `ClientboundSetDefaultSpawnPositionPacket` |
| 100 | 0x64 | `GamePacketTypes.CLIENTBOUND_SET_DISPLAY_OBJECTIVE` | `ClientboundSetDisplayObjectivePacket` |
| 101 | 0x65 | `GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA` | `ClientboundSetEntityDataPacket` |
| 102 | 0x66 | `GamePacketTypes.CLIENTBOUND_SET_ENTITY_LINK` | `ClientboundSetEntityLinkPacket` |
| 103 | 0x67 | `GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION` | `ClientboundSetEntityMotionPacket` |
| 104 | 0x68 | `GamePacketTypes.CLIENTBOUND_SET_EQUIPMENT` | `ClientboundSetEquipmentPacket` |
| 105 | 0x69 | `GamePacketTypes.CLIENTBOUND_SET_EXPERIENCE` | `ClientboundSetExperiencePacket` |
| 106 | 0x6A | `GamePacketTypes.CLIENTBOUND_SET_HEALTH` | `ClientboundSetHealthPacket` |
| 107 | 0x6B | `GamePacketTypes.CLIENTBOUND_SET_HELD_SLOT` | `ClientboundSetHeldSlotPacket` |
| 108 | 0x6C | `GamePacketTypes.CLIENTBOUND_SET_OBJECTIVE` | `ClientboundSetObjectivePacket` |
| 109 | 0x6D | `GamePacketTypes.CLIENTBOUND_SET_PASSENGERS` | `ClientboundSetPassengersPacket` |
| 110 | 0x6E | `GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY` | `ClientboundSetPlayerInventoryPacket` |
| 111 | 0x6F | `GamePacketTypes.CLIENTBOUND_SET_PLAYER_TEAM` | `ClientboundSetPlayerTeamPacket` |
| 112 | 0x70 | `GamePacketTypes.CLIENTBOUND_SET_SCORE` | `ClientboundSetScorePacket` |
| 113 | 0x71 | `GamePacketTypes.CLIENTBOUND_SET_SIMULATION_DISTANCE` | `ClientboundSetSimulationDistancePacket` |
| 114 | 0x72 | `GamePacketTypes.CLIENTBOUND_SET_SUBTITLE_TEXT` | `ClientboundSetSubtitleTextPacket` |
| 115 | 0x73 | `GamePacketTypes.CLIENTBOUND_SET_TIME` | `ClientboundSetTimePacket` |
| 116 | 0x74 | `GamePacketTypes.CLIENTBOUND_SET_TITLE_TEXT` | `ClientboundSetTitleTextPacket` |
| 117 | 0x75 | `GamePacketTypes.CLIENTBOUND_SET_TITLES_ANIMATION` | `ClientboundSetTitlesAnimationPacket` |
| 118 | 0x76 | `GamePacketTypes.CLIENTBOUND_SOUND_ENTITY` | `ClientboundSoundEntityPacket` |
| 119 | 0x77 | `GamePacketTypes.CLIENTBOUND_SOUND` | `ClientboundSoundPacket` |
| 120 | 0x78 | `GamePacketTypes.CLIENTBOUND_START_CONFIGURATION` | `ClientboundStartConfigurationPacket` |
| 121 | 0x79 | `GamePacketTypes.CLIENTBOUND_STOP_SOUND` | `ClientboundStopSoundPacket` |
| 122 | 0x7A | `CommonPacketTypes.CLIENTBOUND_STORE_COOKIE` | `ClientboundStoreCookiePacket` |
| 123 | 0x7B | `GamePacketTypes.CLIENTBOUND_SWING_ANIMATION` | `ClientboundSwingAnimationPacket` |
| 124 | 0x7C | `GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT` | `ClientboundSystemChatPacket` |
| 125 | 0x7D | `GamePacketTypes.CLIENTBOUND_TAB_LIST` | `ClientboundTabListPacket` |
| 126 | 0x7E | `GamePacketTypes.CLIENTBOUND_TAG_QUERY` | `ClientboundTagQueryPacket` |
| 127 | 0x7F | `GamePacketTypes.CLIENTBOUND_TAKE_ITEM_ENTITY` | `ClientboundTakeItemEntityPacket` |
| 128 | 0x80 | `GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY` | `ClientboundTeleportEntityPacket` |
| 129 | 0x81 | `GamePacketTypes.CLIENTBOUND_TEST_INSTANCE_BLOCK_STATUS` | `ClientboundTestInstanceBlockStatus` |
| 130 | 0x82 | `GamePacketTypes.CLIENTBOUND_TICKING_STATE` | `ClientboundTickingStatePacket` |
| 131 | 0x83 | `GamePacketTypes.CLIENTBOUND_TICKING_STEP` | `ClientboundTickingStepPacket` |
| 132 | 0x84 | `CommonPacketTypes.CLIENTBOUND_TRANSFER` | `ClientboundTransferPacket` |
| 133 | 0x85 | `GamePacketTypes.CLIENTBOUND_UPDATE_ADVANCEMENTS` | `ClientboundUpdateAdvancementsPacket` |
| 134 | 0x86 | `GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES` | `ClientboundUpdateAttributesPacket` |
| 135 | 0x87 | `GamePacketTypes.CLIENTBOUND_UPDATE_MOB_EFFECT` | `ClientboundUpdateMobEffectPacket` |
| 136 | 0x88 | `GamePacketTypes.CLIENTBOUND_UPDATE_RECIPES` | `ClientboundUpdateRecipesPacket` |
| 137 | 0x89 | `CommonPacketTypes.CLIENTBOUND_UPDATE_TAGS` | `ClientboundUpdateTagsPacket` |
| 138 | 0x8A | `GamePacketTypes.CLIENTBOUND_PROJECTILE_POWER` | `ClientboundProjectilePowerPacket` |
| 139 | 0x8B | `CommonPacketTypes.CLIENTBOUND_CUSTOM_REPORT_DETAILS` | `ClientboundCustomReportDetailsPacket` |
| 140 | 0x8C | `CommonPacketTypes.CLIENTBOUND_SERVER_LINKS` | `ClientboundServerLinksPacket` |
| 141 | 0x8D | `GamePacketTypes.CLIENTBOUND_WAYPOINT` | `ClientboundTrackedWaypointPacket` |
| 142 | 0x8E | `CommonPacketTypes.CLIENTBOUND_CLEAR_DIALOG` | `ClientboundClearDialogPacket` |
| 143 | 0x8F | `CommonPacketTypes.CLIENTBOUND_SHOW_DIALOG` | `ClientboundShowDialogPacket` |

144 Pakete.

Gesamt: 260 Paketdefinitionen.
