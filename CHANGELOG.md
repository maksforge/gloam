# Changelog

## [1.6.0](https://github.com/maksforge/gloam/compare/v1.5.0...v1.6.0) (2026-10-31)


### Features

* install on Android 8 and later, with newer-only features off below their level ([7852ca2](https://github.com/maksforge/gloam/commit/7852ca24c17a59261f9dda27b091b6a4049d01a7))


### Bug Fixes

* ask for the fused location provider on every level ([6fc3774](https://github.com/maksforge/gloam/commit/6fc37741ab2c818be410d52cb38b11c97695d947))
* dim the left navigation bar and keep the bubble on the display in seascape below Android 11 ([539dfaf](https://github.com/maksforge/gloam/commit/539dfaf576c0ea021b3f0ad2a88ef6ac474fc113))
* give the shade window the 0.8 alpha itself below Android 12 so it is no darker than on 12+ ([dc4a2be](https://github.com/maksforge/gloam/commit/dc4a2befd4bc92768ed83e481e9b005aa0010379))
* pick up the overlay grant on Android 8 without leaving the app ([d9c3e7e](https://github.com/maksforge/gloam/commit/d9c3e7e326fe3f02edcdd42d21fde779ef08a7ad))
* shape the ramp without the window alpha clamp below Android 12 and keep the backlight override above zero below 11 ([0a310dc](https://github.com/maksforge/gloam/commit/0a310dc22096b4df4c215d1d17486ec8078566df))

## [1.5.0](https://github.com/maksforge/gloam/compare/v1.4.0...v1.5.0) (2026-10-30)


### Features

* add Restore to default in Settings ([1b7393e](https://github.com/maksforge/gloam/commit/1b7393eb547bd44d374702072e6320a800f9829c))
* ask before deleting a preset, and say how a built-in comes back ([ffec946](https://github.com/maksforge/gloam/commit/ffec9469e858db99e7cf4ec67fe3026b8b556f30))
* make every preset editable, built-ins included, and keep presets unique ([7cf3b04](https://github.com/maksforge/gloam/commit/7cf3b040c308b796954c5dfc1c5dfc56e47b1b0c))
* pause dimming for a few minutes and restore it on its own ([0e792b4](https://github.com/maksforge/gloam/commit/0e792b473a9c9de4c9250cc50ff130e860e86ee8))
* pause for a length of your own under Other… ([a32a5c9](https://github.com/maksforge/gloam/commit/a32a5c91980b61dee456c87056faffd81832c624))
* pick the pause length on the full screen's chips ([103df8d](https://github.com/maksforge/gloam/commit/103df8d2538ac7a15327a71b2ee042831a702383))
* save your own presets under a name ([c1192a4](https://github.com/maksforge/gloam/commit/c1192a460c15550734237df1de3bf4e06ea401fd))
* show the auto-off time left as a ring on the bubble ([73d793a](https://github.com/maksforge/gloam/commit/73d793a8db8611541d9b5a82aca219dff231b5c6))
* show the time a pause has left as a ring on the bubble and the compact button ([22db5b5](https://github.com/maksforge/gloam/commit/22db5b5c07f41a7aa6d556ef74af4190c8efe702))
* touch and hold your own preset to rename or delete it ([e2ce1ef](https://github.com/maksforge/gloam/commit/e2ce1efea9a474ab2bd988cd8712ed8da576d22d))
* type a custom auto-off length under Other… ([5a2c903](https://github.com/maksforge/gloam/commit/5a2c9035fc861a5886b6053d215ed4207623b9a2))


### Bug Fixes

* bound the compact warmth section by the bar in layout rather than by a label estimate ([9f895ae](https://github.com/maksforge/gloam/commit/9f895ae93a6ad3f0ae7dff3cdbb42e9ec40cce69))
* drain the bubble's pause ring to the deadline, as the compact button does ([b867525](https://github.com/maksforge/gloam/commit/b867525501d170c914fcbe4a3da3f1738195c33b))
* drop the unreleased saved_presets read and list the keys Restore keeps ([94abc72](https://github.com/maksforge/gloam/commit/94abc729770c3876c8172cb0f6167e54e6dc4531))
* end a pause when the warmth colour changes ([5a8f0fb](https://github.com/maksforge/gloam/commit/5a8f0fb0d1c9668fd6ac5fabf298e661def4f0cc))
* gate only git push on owed release notes, not every shell command ([ed1988a](https://github.com/maksforge/gloam/commit/ed1988a3f06ade64822fa68871c0f32d51869263))
* keep a ＋ chip after the presets so saving one is never hidden ([a8d6973](https://github.com/maksforge/gloam/commit/a8d6973eb658e255aca496cb35a7bcb057c4a811))
* keep the panel's close button on a short landscape display ([2dae826](https://github.com/maksforge/gloam/commit/2dae826cf00cb1e3e9fdb3aff97817e8a4b0f8a3))
* keep the Spanish and Polish preset word apart from Settings ([22b568e](https://github.com/maksforge/gloam/commit/22b568e66ae80dc3d26fd4a7f891bd8ebcce1304))
* keep the timer chips still under the finger and bound the section by the bar ([c54593e](https://github.com/maksforge/gloam/commit/c54593efefd1a5d71fee00d8506cd7ce155ab8b5))
* let a preset set the controls without starting the shade ([19365df](https://github.com/maksforge/gloam/commit/19365dfb1b39b696939eba505fa291f71ddeaefc))
* let TalkBack read Resume now on the compact pause button ([10eb936](https://github.com/maksforge/gloam/commit/10eb936399a1a2a086e495f46f596a51ab38232f))
* light every preset chip whose values match, so one saved from a lit chip lights too ([956a555](https://github.com/maksforge/gloam/commit/956a5551778806bd6c566fd6611bc84cfd02cc6c))
* lose only the unreadable row when saved presets decode ([b1390a3](https://github.com/maksforge/gloam/commit/b1390a319b5f7697f1844f3b89133b78fd611c6f))
* merge each pause chip's spoken name into the chip ([7d7fe08](https://github.com/maksforge/gloam/commit/7d7fe08b9273a13e227e84dcab025dcc9df1b1eb))
* name the colour in the presets hint, since a preset sets it too ([85c36b5](https://github.com/maksforge/gloam/commit/85c36b509b487bc7dcfdf2005f8020ef729c93af))
* name the pause range as bare numbers, with no count-dependent noun ([69bcf29](https://github.com/maksforge/gloam/commit/69bcf29792c2b14d0d4e617dc6319e6366f99928))
* open the auto-off minutes field selected, so typing after Next replaces it ([fd29e0c](https://github.com/maksforge/gloam/commit/fd29e0c5be9c80be39f08f952ccc7160b30a38cd))
* open the pause dialog focused with its number selected, and confirm on Done ([687fcc5](https://github.com/maksforge/gloam/commit/687fcc50f3dfe6701414b2b24bf6c203f28452df))
* redraw the pause ring once per degree and empty it clockwise ([2af5422](https://github.com/maksforge/gloam/commit/2af542236840be8ad0c524948d4517b9be3b3411))
* say resume with one verb per language ([4f9a2d4](https://github.com/maksforge/gloam/commit/4f9a2d468011954180757fe6c9227c395d0ba248))
* say the pause resumes, not resumed, in Brazilian Portuguese ([5c9427a](https://github.com/maksforge/gloam/commit/5c9427a5dd53df594f235daa7f38bd6a5b980f7e))
* scroll the lit timer chip into view and test the section bound in the height sweep ([0cc849d](https://github.com/maksforge/gloam/commit/0cc849d0705fb1685a2f1ffbe3ff44914f69d7c1))
* shorten the compact warmth column beside a shrunk bar instead of pushing the buttons off ([7cc8c61](https://github.com/maksforge/gloam/commit/7cc8c613423690b4485382a1bf4ea8ddbfb20500))

## [1.4.0](https://github.com/maksforge/gloam/compare/v1.3.1...v1.4.0) (2026-10-30)


### Features

* leave the bubble when the tile turns the shade on ([879b4c7](https://github.com/maksforge/gloam/commit/879b4c755b12af844ae2372378edc05bd184e06b))
* say the bubble setting also covers a start from the tile ([9861c32](https://github.com/maksforge/gloam/commit/9861c32482934bd240edf26d16c0fcefcfd94721))


### Bug Fixes

* leave no bubble when the tile starts the shade over Gloam ([388b36c](https://github.com/maksforge/gloam/commit/388b36c4f04bf4a61807e9c1e92feacec818f35a))

## [1.3.1](https://github.com/srednimax/gloam/compare/v1.3.0...v1.3.1) (2026-10-20)


### Bug Fixes

* send support mail to gloam.support@maksforge.com ([65fb01b](https://github.com/srednimax/gloam/commit/65fb01be94463702caf20998ff0a301ddcb6e40b))

## [1.3.0](https://github.com/srednimax/gloam/compare/v1.2.1...v1.3.0) (2026-10-11)


### Features

* let users leave a tip through Google Play ([aed8f24](https://github.com/srednimax/gloam/commit/aed8f2478ce02e80f9d851e12416d1e7a5c81db7))
* offer four tips, up to 50 zł, on a wrapping row ([1117994](https://github.com/srednimax/gloam/commit/11179941ce82f15677eaeed216514497756ddcb4))


### Bug Fixes

* drop gendered and ungrammatical forms from the tip's translations ([fd35f3f](https://github.com/srednimax/gloam/commit/fd35f3f9ede22b70cd90bbe1edaf6e017b5a67ba))
* keep the tip's thank-you when the foreground settle consumes first ([5f4c27a](https://github.com/srednimax/gloam/commit/5f4c27a3da338e4386956478649bc82b7ecb80aa))
* settle unconsumed tips every time the app comes to the foreground ([e2c6023](https://github.com/srednimax/gloam/commit/e2c60236deacc3c5c317bd98bbabd0cf477c7cb3))

## [1.2.1](https://github.com/srednimax/gloam/compare/v1.2.0...v1.2.1) (2026-10-11)


### Bug Fixes

* widen the docked bubble past HyperOS's back-gesture strip ([b2b4a3a](https://github.com/srednimax/gloam/commit/b2b4a3abf224299cd1a808c0732da5de0931b12a))

## [1.2.0](https://github.com/srednimax/gloam/compare/v1.1.0...v1.2.0) (2026-10-11)


### Features

* leave a bubble when the controls close ([32dc25e](https://github.com/srednimax/gloam/commit/32dc25e967479488aa4b93510c245ac1cf5864b9))


### Bug Fixes

* stop the bubble's ✕ from blocking touches, and keep its side on rotation ([509797d](https://github.com/srednimax/gloam/commit/509797de02980114ea7f8266f4a2ca51618d557c))

## [1.1.0](https://github.com/srednimax/gloam/compare/v1.0.0...v1.1.0) (2026-10-10)


### Features

* presets for reading, evening and in bed ([3027bd3](https://github.com/srednimax/gloam/commit/3027bd3dda6d425c98fce7eee05cbc60a5f58fe3))


### Bug Fixes

* upload the feature graphic with the listing, not only by hand ([6061ec4](https://github.com/srednimax/gloam/commit/6061ec49ae973b29424f3022ae6a1eda3ac7887e))

## [1.0.0](https://github.com/srednimax/gloam/compare/v0.9.0...v1.0.0) (2026-09-25)


### Documentation

* record production access and write 1.0.0's release notes ([302edda](https://github.com/srednimax/gloam/commit/302edda2135014df1bbcd2f6e742bef27c56d38e))

## [0.9.0](https://github.com/srednimax/gloam/compare/v0.8.0...v0.9.0) (2026-09-18)


### Features

* add the Quick Settings tile that stops the shade ([750b6b6](https://github.com/srednimax/gloam/commit/750b6b636dd444ec035a815d2c4f6e9bd920dfea))
* ask the system to add the Quick Settings tile ([4b492a3](https://github.com/srednimax/gloam/commit/4b492a3599b589ae43a910e92ec2b255f9a9baff))

## [0.8.0](https://github.com/srednimax/gloam/compare/v0.7.1...v0.8.0) (2026-09-17)


### Features

* add a rate-on-Play row to Help and feedback ([5cd654d](https://github.com/srednimax/gloam/commit/5cd654d7cb79a3aca4472ea65a9c19803aa22148))
* ship Gloam in seven more languages ([3705ae2](https://github.com/srednimax/gloam/commit/3705ae2cc15f2bc05e1cc4cc9d078ef70d2980fe))


### Bug Fixes

* refuse a shade intent restored from another phone ([c20bd8a](https://github.com/srednimax/gloam/commit/c20bd8ac65f3ba5b97b18f80c80abf61537b964d))
* skip a locale with no description instead of publishing an empty one ([b6f44ea](https://github.com/srednimax/gloam/commit/b6f44ea7e8a679ecdc933114d887bb5b07b8d0ab))

## [0.7.1](https://github.com/srednimax/gloam/compare/v0.7.0...v0.7.1) (2026-09-16)


### Bug Fixes

* teach the artifact gate that this app locks one screen on purpose ([8da47be](https://github.com/srednimax/gloam/commit/8da47be1877f62a04e80eb5b33e1ca5761484284))

## [0.7.0](https://github.com/srednimax/gloam/compare/v0.6.0...v0.7.0) (2026-09-16)


### Features

* dim the backlight down to the panel's floor ([c4944f5](https://github.com/srednimax/gloam/commit/c4944f5e049edfaed175ee61c8868233d892d536))
* give warmth a colour, from amber to deep red ([5f8d6d6](https://github.com/srednimax/gloam/commit/5f8d6d652fadcfcb5265d6af37805a6caeffba27))
* let the schedule follow the sun, from sunset to sunrise ([e6c75b5](https://github.com/srednimax/gloam/commit/e6c75b592f44dc4f2bd5bb75a9a4fff2b442d5da))
* remove the Material You toggle ([0d347c0](https://github.com/srednimax/gloam/commit/0d347c043fc0d5e536bf0b5c0e2c2009c61d964d))
* say in Settings how to avoid low-brightness flicker ([8ac998d](https://github.com/srednimax/gloam/commit/8ac998d89f1a4cddba41f491935eae75282d9545))
* say in Settings that the lock screen is not dimmed ([d70631d](https://github.com/srednimax/gloam/commit/d70631d8fafb83eb5b48badf3ee872109bdff9d3))


### Bug Fixes

* close the compact controls only once the panel has drawn ([4890e65](https://github.com/srednimax/gloam/commit/4890e6599f6f8601769bced8b6adeedd20cf4cab))
* derive the shade alpha from the light it should leave ([d437f07](https://github.com/srednimax/gloam/commit/d437f07c6422e0912a665a95d06c6bbff1e192a0))
* dim the strip above the navigation bar ([e34ef2f](https://github.com/srednimax/gloam/commit/e34ef2f683b37df211ac2ebcc3995995f76012a0))
* keep one edge bar on screen, the one opened last ([ce2c5c2](https://github.com/srednimax/gloam/commit/ce2c5c2b8a688c5c01f7f9b5536d8dbf55f1e41b))
* keep the dim through a rotation with Gloam's own screen in front ([8241af2](https://github.com/srednimax/gloam/commit/8241af26e0c0d950915d6b01d0505aa88a2522cc))
* keep the full app in portrait ([d08e101](https://github.com/srednimax/gloam/commit/d08e1019d5e8210cb23c83eb501d1a90b2fea5e6))
* offer Start when HyperOS killed the shade ([b969f88](https://github.com/srednimax/gloam/commit/b969f88211fb4e35982bb8db1e2cb6c4bfb16e7c))
* put both edge bars on the same pixels ([e265795](https://github.com/srednimax/gloam/commit/e265795b765b1a63a40b88bb34e607f6cca759ac))
* **test:** wait for the panel's relayout before reading its width ([ab3f391](https://github.com/srednimax/gloam/commit/ab3f3918d2fece06d7013b8e0703c73bcbf03280))

## [0.6.0](https://github.com/srednimax/gloam/compare/v0.5.0...v0.6.0) (2026-09-08)


### Features

* dark is the default theme, not the system's ([d01ffb6](https://github.com/srednimax/gloam/commit/d01ffb6612eb969725bcecf2bd983ce21cd71938))
* dim on a nightly schedule ([b961b9a](https://github.com/srednimax/gloam/commit/b961b9ad7b1c8419cf101b0c95349080ecc3e95f))
* palette A, from the ember seeds ([180d3b5](https://github.com/srednimax/gloam/commit/180d3b5dc91808c8e491bc47f9f5b9bedb50f272))
* put the icon's ground on the night instead of the mud ([159113b](https://github.com/srednimax/gloam/commit/159113b9dd5ffd296825c9d444d92f53167cee02))
* replace the placeholder mark with the moon ([2feadf0](https://github.com/srednimax/gloam/commit/2feadf0fe6eedc216cfac26dc2b9f4028f2544f5))
* the dim level is a column, and the compact controls an edge bar ([eedfcac](https://github.com/srednimax/gloam/commit/eedfcace8bc3bef3e36c1a0372d0127d1ab72b7e))
* the launcher icon's ground goes flat ([9ddc9ce](https://github.com/srednimax/gloam/commit/9ddc9ce3ec8e8b1c758cecec82b7e5bd28b3ff30))


### Bug Fixes

* doze-capture says whether the night is over, instead of ending at a zero count ([e482204](https://github.com/srednimax/gloam/commit/e482204332127100626c0fc2ba8257833becb956))
* keep the feature graphic inside Play's 16:9 crop ([f976de4](https://github.com/srednimax/gloam/commit/f976de4202d1af51e0cd5f1abda38b6d64a283bf))
* sweep the two call sites the refactor's sweep missed ([0332a88](https://github.com/srednimax/gloam/commit/0332a88cff0a5a9d9ce415b005904b637c324e6b))
* take the notification down with the shade, not a moment after it ([b8fba4d](https://github.com/srednimax/gloam/commit/b8fba4dee7404b85cc7f372b2bef207f595c3e9c))
* take the shade down when the screen comes on, not a minute later ([e779c38](https://github.com/srednimax/gloam/commit/e779c38faf5c518f18c1672e3e5e5e248e52e124))
* the compact controls centre on the edge, and a tap outside puts them away ([cf8bc27](https://github.com/srednimax/gloam/commit/cf8bc2762fd9d5fea44c63eab893eede5856d31d))
* the icon lands on the compact controls, so the full app cannot flash behind them ([61b07d6](https://github.com/srednimax/gloam/commit/61b07d63724ecf7051f1c14cdbd4b5439c6b3f64))
* the launcher icon opens the compact controls without flashing the app ([ba855ac](https://github.com/srednimax/gloam/commit/ba855acba760260f8dae7abf7afe5ef18c442d4a))

## [0.5.0](https://github.com/srednimax/gloam/compare/v0.4.0...v0.5.0) (2026-09-04)


### Features

* add the compact controls ([744d9df](https://github.com/srednimax/gloam/commit/744d9dfd9d8df0336996a0156629f7345388d309))
* give the compact controls a bar and three buttons ([c87ad8f](https://github.com/srednimax/gloam/commit/c87ad8fa5d1b2442ebd4aff43c3f50d2d05fe24d))
* open the compact controls from the notification and the icon ([3c2146e](https://github.com/srednimax/gloam/commit/3c2146e04146aa7b1e372d16109c1ac97dcb4f26))
* put the controls in a window above the shade ([012459c](https://github.com/srednimax/gloam/commit/012459ca4d64f44dcc11fdf135d21c0309ce5a3f))
* say in the notification that the brightness slider is paused ([2e3f908](https://github.com/srednimax/gloam/commit/2e3f908045b3ccfbead56a7733c0159e7d0f8af8))


### Bug Fixes

* honour the icon preference when Gloam's task is already open ([94e5396](https://github.com/srednimax/gloam/commit/94e53969acf5b8e32444ab31030ad08ed4450209))
* re-measure the panel when the display turns ([74ea7cc](https://github.com/srednimax/gloam/commit/74ea7cc5cccfc0afee84e95006452a1e45a9ed01))
* stop a summon from raising the shade the user turned off ([eb3d33a](https://github.com/srednimax/gloam/commit/eb3d33acaab2ba71698496945a1c5ee25b8b038a))
* stop counting the navigation bar twice under the panel ([23fc329](https://github.com/srednimax/gloam/commit/23fc329ccf0e342d6c3a16b06f63707a1f27a554))

## [0.4.0](https://github.com/srednimax/gloam/compare/v0.3.0...v0.4.0) (2026-09-01)


### Features

* add a Help and feedback screen ([ad06566](https://github.com/srednimax/gloam/commit/ad065661fdd5a365fb7777388f7e0615e3fb9251))
* put the shade back after a restart ([872e743](https://github.com/srednimax/gloam/commit/872e743167fd08fcd8a241b95d8b70504817443a))
* take the shade down on its own after a while ([1231b17](https://github.com/srednimax/gloam/commit/1231b17106451dca34a287e6b57015052710801e))


### Bug Fixes

* log the restore under a tag the platform does not already use ([280caa6](https://github.com/srednimax/gloam/commit/280caa62043167fee52fa43229ee3310d5e5114a))
* make device-gate report what it actually read ([778cbc1](https://github.com/srednimax/gloam/commit/778cbc1f92bf5d5ff50c48defdac5387f653e70a))
* point device-gate at the label the phone actually shows ([50a7017](https://github.com/srednimax/gloam/commit/50a701772f8f19e3ac988fb9b8a7d6fa5e94aca5))

## [0.3.0](https://github.com/srednimax/gloam/compare/v0.2.0...v0.3.0) (2026-08-31)


### Features

* ask for notification permission before the first shade ([ad2fe81](https://github.com/srednimax/gloam/commit/ad2fe81d8ecfd911541617e17caf62408a89d790))
* take the backlight down with the dim level ([1f6720e](https://github.com/srednimax/gloam/commit/1f6720e076dee98cee8619e75017f12ff9ecae6f))
* tint the shade amber ([6a56ccc](https://github.com/srednimax/gloam/commit/6a56cccd2bea332307bfcacdff70eb881c09b2bf))

## [0.2.0](https://github.com/srednimax/gloam/compare/v0.1.0...v0.2.0) (2026-08-30)


### ⚠ BREAKING CHANGES

* devices below Android 13 (API 33) can no longer install Gloam. Roughly 30% of Android devices are excluded. Nothing is published yet, so no existing install is stranded.

### Features

* bootstrap the template into Gloam ([4fd4392](https://github.com/srednimax/gloam/commit/4fd43925d623fd0af72d309cd072e82fa19092ec))
* give Gloam a dusk palette instead of the template's slate blue ([ad4003e](https://github.com/srednimax/gloam/commit/ad4003e5877f4ef2fe921b7647fe3ce6ef7d406e))
* require Android 13, raising minSdk from 26 to 33 ([5dd8afa](https://github.com/srednimax/gloam/commit/5dd8afaa9e5b8f2c78b08e150afca6cf0387c2e3))
* strip the template's data layer and add the dimming shade ([e2ebc68](https://github.com/srednimax/gloam/commit/e2ebc68e84cbd06d43acc15bf138344c4abc6f26))


### Bug Fixes

* carry the shade over the status bar on HyperOS ([b8e8c35](https://github.com/srednimax/gloam/commit/b8e8c35b8d3e1608176fa2bdddff52d896c196ef))
* correct the artifact permission inventory to Gloam's own set ([f7b016e](https://github.com/srednimax/gloam/commit/f7b016e41a3745cb602fd83a3627d9dabe36576f))

## Changelog

Nothing released yet.
