package com.suarez.coder

import com.suarez.coder.decision.LocalRouter
import com.suarez.coder.decision.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRouterTest {
    private fun r(s: String, pinned: Int = 0) = LocalRouter.classify(s, pinned)?.route

    @Test fun shortUiTextRequestsGoToEditor() {
        assertEquals(Route.SIMPLE_EDIT, r("Giriş butonunun rengini mavi yap"))
        assertEquals(Route.SIMPLE_EDIT, r("Başlıktaki yazım hatasını düzelt"))
        assertEquals(Route.SIMPLE_EDIT, r("ana ekrandaki padding değerini 16dp yap"))
        assertEquals(Route.SIMPLE_EDIT, r("change the icon of the settings button"))
    }

    @Test fun singlePinnedFileGoesToEditor() {
        assertEquals(Route.SIMPLE_EDIT, r("Burada sayacın sıfırlanmasını engelle", pinned = 1))
    }

    @Test fun complexKeywordsGoToArchitect() {
        assertEquals(Route.COMPLEX_ARCHITECT, r("Oyuna yeni bir seviye ve puan tablosu ekle"))
        assertEquals(Route.COMPLEX_ARCHITECT, r("Uygulamaya yeni ekran ekle: ayarlar ve profil"))
        // A refactor is not automatically architecture any more: tier 2 / Decision decides.
        assertNull(r("Ağ katmanını refactor edip Retrofit'e taşı"))
    }

    @Test fun scopedFixesBypassTheArchitect() {
        assertEquals(Route.SIMPLE_EDIT, r("Uygulama açılışta çöküyor, logları inceleyip düzelt", pinned = 1))
        assertEquals(Route.SIMPLE_EDIT, r("Giriş ekranındaki null hatayı düzelt"))
        assertEquals(Route.SIMPLE_EDIT, r("app/src/main/java/com/x/LoginViewModel.kt içinde timeout değerini 30 sn yap"))
        assertEquals(Route.SIMPLE_EDIT, r("PR #12'yi merge et"))
    }

    @Test fun questionsWithoutChangeVerbs() {
        assertEquals(Route.QUESTION_ONLY, r("Bu projede state yönetimi nasıl yapılıyor?"))
        assertEquals(Route.QUESTION_ONLY, r("ChatViewModel ne işe yarıyor"))
    }

    @Test fun unsureCasesGoToTier2() {
        assertNull(r("Sepete ürün eklenince toplam fiyatı yeniden hesapla ve kaydet"))
        // Long message with a UI word is not "short".
        assertNull(r("Kullanıcı profil sayfasında, sunucudan gelen verilerle birlikte başlık alanının da güncellenmesini ve kaydedilince önbelleğin temizlenmesini sağla lütfen"))
    }

    @Test fun parsesOneWordAnswers() {
        assertEquals(Route.SIMPLE_EDIT, LocalRouter.parseOneWord("SIMPLE"))
        assertEquals(Route.COMPLEX_ARCHITECT, LocalRouter.parseOneWord(" complex."))
        assertEquals(Route.QUESTION_ONLY, LocalRouter.parseOneWord("Question"))
        assertNull(LocalRouter.parseOneWord("maybe"))
    }
}
