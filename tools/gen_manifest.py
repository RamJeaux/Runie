#!/usr/bin/env python3
"""Regenerates src/main/resources/com/runie/assets_manifest.json.

The IMAGE_IDS table below is the FINALIZED, QA'd sprite manifest for the
full v3 42-line roster (kawaii + pixel, stages 1-3), sourced from
build_bestiary.py CREATURES (the canonical art manifest — includes the
Skitterfang->Skitterscour rename and all pixel fixes). Each id resolves to
    <baseUrl><id>.png
on the platform that hosts the approved concept art (session auth required —
see tools/fetch_assets.sh). Resource paths follow architecture §5.1:
    com/runie/creatures/<id>/art/<style>/stage<N>/<anim>/frame_###.png

Run from the repo root:  python3 tools/gen_manifest.py
"""
import json
import os

BASE_URL = "https://hyperagent.com/api/files/usergenerated/threads/cmrp4d2oe0o6107ad0k8egpg2/images/"

# creatureId -> [kawaiiS1, kawaiiS2, kawaiiS3, pixelS1, pixelS2, pixelS3]
IMAGE_IDS = {
    "grubnak": ["32688ecc-d5d1-4474-83ee-85466b22c04e", "01595650-9ed6-4bcf-92cc-76b1451789fe", "7c1bdd4b-a1a2-42e5-945d-078f07b5aaf5", "4957cbed-643b-45e4-bcf4-b13ad1c4881e", "af12be5c-bfeb-4d37-9ded-93b79a899467", "3e37aa13-5915-43c3-8a1c-a7bd30dc1c7d"],
    "skitterscour": ["82a9dca1-fe76-4b77-ade6-1eb23e9c5749", "5799b31b-bb0d-419f-bce1-b7fb21eb433f", "2ab07224-9d6c-4ab0-8b7b-36fa33b5239d", "d309fd68-c0af-4abb-95dd-fe243d363269", "a1b19452-bfbc-4691-a375-d781f26f7f36", "84660987-3cee-4254-9712-5860908b28a1"],
    "cluckabee": ["729e108d-4a64-458a-bd60-37b8bf99c9da", "f9e357d2-7d5f-4c84-9476-32c80d770ac0", "5a1e7b09-a264-4a3e-aa03-ae882e129514", "1c7332ef-836e-4ea9-b6e4-b19978fbe430", "b38f11b6-8011-43b4-9a92-e1cca325f2b6", "7855ef53-095b-48ed-93d3-c1a6b12445eb"],
    "woolder": ["71c4f7f4-9a88-49c9-b2b9-44293462dec9", "4e381721-2980-4d34-80cf-789b4802c87c", "43661377-a1e5-4b74-8e3c-af7bab4cb9d8", "f7ef52d0-4a58-40d0-95ed-fc5e1ea29fe2", "ae5775ab-d7be-4a2f-a91f-9865ec39a1de", "e12b07ca-4a47-4235-8d55-bc4ded7d6fb1"],
    "pindlewick": ["b1be7334-c80e-4397-9791-2e32e2b83be1", "0d5debed-dd7d-4db7-bfba-790cfbb3a047", "de80e580-d370-4060-86af-0b0ca0f2dc9c", "3806cfd6-e62c-4bd4-b0e1-9f2aede7cc4e", "ba18f047-5288-495b-ae45-04ceed2286f6", "aa2e0895-8d0e-4627-a046-57a61d61b8bd"],
    "bananip": ["91168c0e-f9e3-4272-9685-c8bae999b623", "0fe4c470-4d3e-431c-9ec3-343b1f5097c0", "cb4736f7-d25b-4434-9f5f-41e69a708d91", "55c5e3d7-2f67-4616-a00b-6ecde275fab7", "8d0a6e2f-f5ed-446a-9ab6-09caf8bf1ea9", "af1ad01a-71fd-4ca0-87d6-416f5acd4688"],
    "grottlecap": ["3d903bcf-cbe2-486b-b2dc-f4b2db4c9b7c", "2f223e42-f941-4d5e-9634-277fc8eefe5f", "28cfab6d-ce88-4a02-bf9f-f0b60ff0a81b", "518f62c7-50e8-4e6e-9ae3-a86d98cd2654", "1ce72c19-6d43-409e-a1fa-e09fd6360ba4", "70fe6dec-3694-4e46-be9b-3f95412c3e11"],
    "clackerdown": ["5e55c3cc-afda-490d-b8dc-f1560aa9f60f", "2f7e2cdd-8bb8-4c10-af40-4381dac9fb99", "595f7868-f874-457f-91e6-f6594187483e", "86a0bf3f-9d4a-4d9e-b3c9-3f22a10a3245", "956ff0d0-0d94-4ddc-9916-fa37eb9dbf3c", "749e2c3e-34ba-45c0-baba-7cc8dca7f437"],
    "barkuff": ["d3be6f8f-090e-45ce-82c6-9f600a731495", "145ae7be-537b-416b-b79d-e29b34304f83", "d8e9b5d5-ec58-4237-af7a-8b385828034f", "77edda63-5729-4537-912d-bac0a547751f", "41ddc9ba-9485-43d8-8db9-d728022667c0", "e2fb35dd-cec8-4f10-940b-4ef168c643fc"],
    "bouldergrum": ["67866493-8191-44d9-a244-1a64edaab57a", "5934f37c-9781-4a22-9573-b970e0f3d078", "487b9e22-2d1d-4039-94a8-1f51572ee7e9", "05dbb300-634c-473e-9dc3-7485d8e9dbc7", "30cbe041-1602-4f41-8387-918147893715", "2d0ecc96-de20-4f2e-a89a-46413368e6b1"],
    "ratterbone": ["db28521a-d97f-4c9d-8428-55d9d99a04bb", "eb5edb33-d2be-4531-9a92-9306c564d2c1", "81f12f1a-6972-449f-ba03-0763348bc133", "8573a386-3b1d-41e5-a86c-255ffdb526cb", "76b8f762-af0b-4d77-8e5d-70911239b813", "ed453012-dce4-4f3b-95ae-2d4f55d8114c"],
    "wisplume": ["58db99a1-73d1-4f32-a059-cb6512c09f04", "1e6d06db-30ec-4809-ba64-4e0b4c14d194", "0495ef53-8f05-4d68-bb0c-359e5f4a1cef", "c8f22a35-2215-420a-9de6-9107e6ce8fd3", "b29792c3-557e-44ca-9203-7a87d9a23538", "12c3014c-893a-4ae6-b8b2-d1a98c5f2248"],
    "glabbertongue": ["528f1c96-c7e6-4009-abfb-dd60b7fbe9df", "7e0c0c1c-bc38-4364-9efb-1687094305f0", "f0b804f6-f1b0-4cde-a8bc-d68bd03b6736", "6f0d5eed-7ce9-4301-9bd4-f6f65ce9c652", "9895bc18-e7f1-4b1f-82ad-44d61df1d105", "f63ac4d7-cb72-4106-b401-147dcdc80dff"],
    "stingrilla": ["b8bb8cb6-85e4-42b2-9258-ea9c7bbabc36", "a7ff6a41-2923-44fb-827c-b9781c87b5ff", "a7b21284-9613-4256-a336-3d6b56384788", "9e1a6d6d-45dc-4378-a53c-a5a609d36041", "69742737-ed68-47a9-89f3-bea0e7742379", "b30bf0bb-d141-47be-a28a-e5ae6744e960"],
    "grimshank": ["937cc9e1-8530-42e8-a4a3-aa351d9ea2a3", "8d01b12c-d1fe-4487-862a-3ccc3453411f", "edf6fae6-4684-4387-be18-d023aff5061d", "712822c4-8694-425f-bcc6-8289dc53c5e0", "115ac69e-2c18-40da-bbca-f91645571e2b", "5f4f73fc-22ac-445f-951e-933d44d51b7c"],
    "howlrend": ["b1f951da-8653-4544-9d5d-aae7bbee53c4", "41a5927a-a529-4e2b-8a45-f90d51ea3874", "827f261c-ff49-47ea-9f41-652e60cdd8a3", "6343e74e-3a69-429d-8989-f20d0317cdc7", "c1c07897-643a-43d4-8a5e-5476ef2ba52f", "6d2a919f-5d57-46db-a6c4-4e6730a87b3f"],
    "moankettle": ["259c2417-d66b-42f5-bc81-c82756a01ef2", "eb5a4900-dda9-47c7-8d0a-aa3c83800f99", "f17a8dc7-8c7f-4711-b5fe-40c7b01ac2ce", "8e31f889-0f6e-42c8-835b-bec8348729e3", "46842c18-15da-4b01-980b-68111e1cc80d", "5337e218-3516-45b5-913f-d7ef1aabf2c1"],
    "gillcrest": ["b2bbbad7-9a78-423a-b2be-5ac75b036b92", "d340fd77-0f0f-476f-9577-927c2c05ece2", "0f639b29-a5ea-4d93-9497-a56959883607", "158dcd29-6786-4ea9-97ec-23cf1f022805", "3dd00866-13d8-4830-a7e8-9578e173335f", "b5fac76a-0f35-4ce7-ba5d-947a02d8fe6f"],
    "knucklemaw": ["a131f092-869d-4f44-8343-e771c45aae9e", "375f7f07-3a2f-4fae-a785-d144ceb236ae", "20d32ca4-f01c-4570-991d-64db9a84647b", "d79f11c3-bd18-4940-a2de-e40c7ba4d83c", "1f3ada65-2b63-4fd4-8403-5833fac2cb75", "7ef7dc61-bbcf-4ff3-937b-552bf2d34e6f"],
    "tidecoil": ["66c45e57-9ee4-4cfd-983f-1c7f3619a2ed", "697256fa-eb4c-453c-8c1c-cc5c3152d1f0", "5f7616bd-81d8-4e2a-a490-104c5e78e133", "bd29b7d0-c6ed-4354-bf9c-99f4c94ba844", "3569f689-707f-4919-abd8-35b640c64981", "dde61638-bf05-4405-9880-c042567ee435"],
    "cindermaw": ["e74e6164-de1c-48c2-b1be-39ce8271fd8b", "a638881b-42e5-4b96-b8fc-ac4ef9520b2d", "0ca95b13-9add-44f6-a07c-590cfada6e45", "7feaa1ce-9566-4d1d-a4a2-e1e092baad52", "6abb5a55-5b83-4ce6-8e15-94d80c61ef12", "bf65cfa2-1d0b-4b34-b8c0-c6260464d6be"],
    "grithstone": ["d6600d28-c2aa-4aab-b518-1b51f325bc65", "63c20731-abe7-4189-b3de-7d930597f2ae", "6addaf16-0e8b-4225-a713-0ccdb4a5850a", "a4467587-ecc6-4de9-9564-bcce732f1260", "0cf2e75f-d889-4321-bcce-09506505afbb", "1c7d9fc8-e7b6-46ab-87af-6da59bc955c4"],
    "emberwyrmling": ["d801a504-ea8e-4c1e-904d-b46f237e5b7c", "df5f757a-82ef-448a-97ae-93896564db53", "104ed30d-d273-41c8-b4f2-cc424ccb3348", "3edb1f4d-1096-48e4-abe4-f3c5255629c6", "4a28511e-7e38-4a76-b622-8ba0cb1b7c66", "e25b064c-a677-4cdf-82c6-fee581099790"],
    "basilyre": ["b4e1ee66-7a27-4b37-a9f5-13999f8b8f5e", "8806e3e7-0f9d-4a94-becd-99851be64663", "57766eee-e516-4fd2-abe8-c5522869a6ca", "8e5df5a0-9929-4575-800f-69ed715db4b5", "f8c3fad1-15d4-4d84-845c-68031b45cfb5", "3746efa1-294d-4838-b507-9b6cf488a543"],
    "thornkarr": ["cdbe6003-9472-4632-81d1-7e7e4b83c8c6", "90be14cc-5c13-450b-b732-f0b9be0a0868", "1fce78bf-1ad8-4149-80a0-6337713562a4", "fa9a2a48-860f-4091-a73e-d6c5e0a6ab78", "2e12beab-7f6f-42bb-98a4-2355cc118458", "4f1ba65d-212b-42d2-89ad-3cf65bf838eb"],
    "zalthisk": ["4fc0fbac-4657-448b-8356-41b63d3410fb", "c15b7c1e-d53e-4d42-bd34-5e5e67f92c8b", "e8d21aea-cd2a-4de1-9acd-c0ca55fa9fb4", "d2370b59-fe40-49d1-9fef-68fb2a0f11cf", "3a844ff6-967c-4e0c-8ab6-357f45bfa374", "22d16c99-efe9-40f0-84b1-fc9ae1585a71"],
    "pyrgeist": ["3c88dc91-2ae4-4806-a220-f00d6bb7a7c5", "ec9ffdc2-3285-4365-9137-f9b360ed7c74", "5e252961-cb09-4311-b815-fddd8ac0e9b2", "21b6ec25-d970-4b4b-a787-97e870f5a573", "0189420b-f009-4ac1-9d5e-d122a573e518", "9e10165b-6498-4220-81cb-6c4c6515c306"],
    "dredgemaw": ["e8da945e-431b-4004-8da9-1a9291200871", "14ca0185-9280-4dde-bca2-553d9ef3ec35", "efd68f2d-2a28-4b27-8587-9a49eabefbd1", "ab8f57a4-caad-4f0b-b4cd-24f1b4310799", "3e5dc1c6-c917-4920-8b73-7d94cfeb6c14", "68c762cb-7da7-453b-8329-f130c794c99b"],
    "duskrend": ["9f7f58e0-77e2-40fe-8de5-9802efe34156", "050c92cf-d2f5-45c2-828d-4f439bb21dcf", "f327fd5e-9f19-4519-8605-4e4fe775bbfd", "55691b8c-2ce3-4f65-a0e1-3cddfd214c5c", "2e395425-b6c6-4118-9b44-1a336ebfa57a", "905233e0-4d9e-4e9a-bbca-3c5723081d5a"],
    "talonrend": ["82bc6e89-7b65-4d57-a8a9-6cd8d6c1a949", "50518546-5bd5-48bf-9c44-e3ac6f0ec0ee", "f80c8d1a-fa5f-45ff-9e96-71573e59e5c4", "5181f411-551f-43bb-8c34-07af6c4fff80", "4452afc5-f9ba-4f9e-8046-7a912f7cbff7", "38150681-485e-439e-9531-edd289928280"],
    "nechrallis": ["3d7a1bc5-dbf8-4cfd-abdc-24a57ffc2107", "295082de-7571-489c-8f3b-cdcdca29e6ae", "06c97beb-c296-4b39-8192-7182517dbbb3", "042ba0c1-c220-4053-a4fa-33470c6af96b", "b4491494-8a56-4f7b-a302-21638f050067", "570524c1-e698-469d-b387-38f3141bf746"],
    "umbrask": ["b16b979b-5f34-466f-b484-bc8bf59d13f9", "d39192fb-ddc8-4796-8f4e-d4f636875e5a", "1a69162e-da6a-42d6-8be6-daefc01616e6", "1e26bca5-027b-42f9-8d7c-03109442e249", "1a8398a3-313f-4bd1-89db-91414af85d45", "31733468-0bed-4059-9700-708e0654bb01"],
    "vornathax": ["894df587-50aa-4be9-b32f-135e505dfc67", "b0963d5b-398b-4c28-bc32-916704009547", "22f030ec-b9a0-4787-842a-65b67a423254", "881af369-1f5b-4245-a788-fffaa182fdfd", "b9faf813-312f-4499-a46a-7646f10bfb1f", "ec0f276e-2159-4e58-bec7-7c9a430777e0"],
    "coilfen": ["6fd3a364-1cef-4342-910c-562c336bff94", "60bf1d9f-26fe-4930-8bf8-7f72bc9ec813", "f5b8c39f-d548-4245-8e3e-bf00ac2418c0", "0e4ce184-990c-464b-99b0-071ae2c814a7", "ba504a40-41ed-4229-9715-89654ad04cc0", "5416dfaa-6718-4906-aaf6-0a19e7dab307"],
    "vaelgrim": ["aab83b51-b2b2-4aef-b7af-f7630f8182c3", "cd887a0e-e527-46b9-b407-b8c167de6cb2", "1a17dc77-7f68-42d2-920e-1adcf8349576", "0a4dfa2e-1c20-475c-9a37-7cc63822d5c8", "dbfd3159-8789-45d6-9a16-42088ba98271", "2317fd82-d0ad-4b4b-ae6f-8e8509fc755c"],
    "solenfyre": ["2ba24dbc-9d94-4560-b5c1-b2ac88ca9234", "71a059ea-6574-47d5-b666-a779836e4745", "e1230e5f-e1a2-4fa2-94dc-4caf209fbf95", "1bbcceb3-2a35-4bec-a270-a0347593ac7d", "c2ea1384-5797-402a-acf4-32b349b70c56", "893ed121-eb58-4f02-b045-0b3ecf0946e8"],
    "trivandire": ["4f99c898-ede9-4977-8e82-dcf3eae84d3d", "43477363-ae47-4838-b0d0-93c35c7951e5", "3441c23d-2276-4303-b3bf-bc0238aa6a13", "e18b4740-8b7a-4fd0-9d09-5b2402e7ec74", "198869fd-7a21-4ae5-b464-7de31d6edaf6", "5aee0363-1723-4794-bd58-88fef0ce223b"],
    "abyzarok": ["99fee2a2-ad7e-4e72-9af2-c8f1c8ea5cdf", "d9d14ff2-3768-4901-b558-0835178d4678", "f47093ab-0a59-45ec-a93e-ba8a927aea47", "e929f13d-09af-40fc-948d-ec0bf0dfad98", "68559798-d85d-400f-96a8-f957bb4955b9", "42392764-b344-4731-b358-ff40084e496e"],
    "malgrave": ["fc09096d-e9b9-475e-9553-aab0920930ef", "d52a039a-42a3-4050-8dde-78b4963642b0", "2c78522a-0ee2-4018-8a0e-f8dc2fc736a2", "c2de8ed3-7a34-4dca-a304-a87c245897e3", "6ffc560c-77b2-4961-8cb3-beb47cb5337b", "fc1dc9e2-89d2-4b19-b2b4-3195ca52643f"],
    "ythrax": ["cac9db63-6be6-450b-bfa9-6306109f695c", "5df2a531-d2c1-4075-b55e-cc1639c3e3ce", "44b2b3e7-1c99-427c-b373-7a12aa668d84", "3419ba96-7bad-4a26-bd3b-e9bba8b52238", "1ea9e98f-4911-45af-a667-8f5044d3c0ca", "b7c4105d-a96b-4148-9bc2-c3d17e56d0c1"],
}

STYLES = ["kawaii", "pixel"]


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    roster_path = os.path.join(root, "src/main/resources/com/runie/creatures.json")
    with open(roster_path) as f:
        roster = json.load(f)
    roster_ids = [c["id"] for c in roster]
    assert sorted(roster_ids) == sorted(IMAGE_IDS), (
        "manifest table out of sync with creatures.json: "
        + str(set(roster_ids) ^ set(IMAGE_IDS)))

    entries = []
    for cid in roster_ids:  # roster order
        ids = IMAGE_IDS[cid]
        assert len(ids) == 6, cid
        for si, style in enumerate(STYLES):
            for stage in (1, 2, 3):
                image_id = ids[si * 3 + (stage - 1)]
                entries.append({
                    "creatureId": cid,
                    "style": style,
                    "stage": stage,
                    "imageId": image_id,
                    "url": BASE_URL + image_id + ".png",
                    "resourcePath": "com/runie/creatures/%s/art/%s/stage%d/idle/frame_000.png"
                                    % (cid, style, stage),
                })

    manifest = {
        "version": 1,
        "generatedBy": "tools/gen_manifest.py",
        "baseUrl": BASE_URL,
        "note": ("Approved (QA'd) concept sprites for all 42 lines x 3 stages x 2 styles. "
                 "URLs require an authenticated platform session; run tools/fetch_assets.sh "
                 "to install the real art over the bundled placeholder tiles. Only "
                 "idle/frame_000 is sourced here — extra frames (multi-frame idles, "
                 "aura_gold variants) are produced separately and are never overwritten."),
        "entries": entries,
    }
    out = os.path.join(root, "src/main/resources/com/runie/assets_manifest.json")
    with open(out, "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print("wrote %s (%d entries)" % (out, len(entries)))


if __name__ == "__main__":
    main()
