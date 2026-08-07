package com.passpony.android.store

/**
 * Screenshot-grade demo store: enough folders and entries to look
 * lived-in, TOTP on several so the ring shows, usernames and urls on
 * most. Every credential is fake. Ported verbatim from PassPony iOS's
 * AppModel.seedDemoStore so the two apps show identical demo data.
 */
object DemoSeed {
    val ENTRIES: List<Pair<String, String>> = listOf(
        "web/github.com" to "tr0ut-mantle-vivid-42\nusername: kevin\nurl: github.com\notpauth://totp/GitHub:kevin?secret=JBSWY3DPEHPK3PXP&issuer=GitHub\n",
        "web/gitlab.com" to "brindle-ox-cardigan-7\nusername: kevin\nurl: gitlab.com\n",
        "web/google.com" to "quartz-lantern-mirth-19\nusername: kevin.stewart\notpauth://totp/Google:kevin.stewart?secret=NBSWY3DPO5XXE3DE&issuer=Google\n",
        "web/amazon.com" to "saddle-comet-birch-88\nusername: kevin@example.com\nurl: amazon.com\n",
        "web/netflix.com" to "velvet-abacus-north-3\nusername: kevin@example.com\n",
        "web/reddit.com" to "gable-onyx-pretzel-51\nusername: norsehorse\n",
        "web/wikipedia.org" to "ember-latch-copper-24\nusername: NorseHorse\n",
        "mail/fastmail.com" to "battery-staple-orchard-6\nusername: kevin@example.com\notpauth://totp/Fastmail:kevin?secret=MFRGGZDFMZTWQ2LK&issuer=Fastmail\n",
        "mail/proton.me" to "walnut-frost-gallop-77\nusername: kevin@proton.me\n",
        "finance/paypal.com" to "cinder-maple-quill-30\nusername: kevin@example.com\notpauth://totp/PayPal:kevin?secret=JBSWY3DPEHPK3PXQ&issuer=PayPal\n",
        "finance/wise.com" to "harbor-tulip-anvil-12\nusername: kevin@example.com\n",
        "finance/bank.example" to "hunter2-but-much-longer\nurl: bank.example\nnote: ask branch for wire limits\n",
        "work/vpn" to "juniper-cobalt-relay-9\nusername: kstewart\n",
        "work/slack.com" to "pommel-drift-signal-64\nusername: kevin@example.com\nurl: norsehorse.slack.com\n",
        "work/jira.example.com" to "griffin-mortar-plume-28\nusername: kstewart\n",
        "wifi/home" to "pony-passphrase-9000\n",
        "wifi/office" to "stable-gateway-4242\n",
        "dev/registry.example.com" to "tarpit-nimbus-octave-55\nusername: norsehorse-dev\n",
    )
}
