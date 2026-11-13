# FT182 localization fallback audit

Source: initial source-only CI https://github.com/cn-openivi/ft-app/actions/runs/43430065347, SHA 4164a57d3646df69b71b645af0c3e4ed05bbefdf.

The Kotlin application and JVM test sources both **compiled**. Of 912 executed JVM tests, 910 passed and 2 failed: StringsTest.everyLanguageHasEveryString and StringsTest.everyTranslationKeepsTheSameBlanks.

Root cause: 607 translatable string keys in values/ and values-ru/, versus 489 in each of Arabic, Sorani and Badini. The same 118 FT182 additions had no verified translations in those three locales. XML changes insert these 118 keys with temporary English text and preserve all positional format placeholders. This keeps the existing strict `StringsTest` checks **unchanged**, without fabricating Arabic or Kurdish translations. On Android the keys previously already fell back to English when missing, so there is no worsened visible translation for those keys.

**Translation debt remains:** 118 English-fallback entries each in Arabic, Sorani and Badini must be localized and reviewed by proficient speakers. String parity does not imply language quality. Do not describe this as complete Arabic/Kurdish localization.

No APK, signing, release, runtime phone or HU test was authorized or performed. Run source-only compile/JUnit again after this patch.
