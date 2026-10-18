package fr.parisfogofwar

import fr.parisfogofwar.data.image.monumentImageCandidateScore
import fr.parisfogofwar.data.image.isReusablePopRights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCandidateVerifierTest {
    @Test
    fun petitPalaisCandidateMatchesItsWikipediaAlias() {
        val score = monumentImageCandidateScore(
            candidateText = "Facade of Petit Palais PA00088878 (2).jpg",
            monumentName = "Musée des beaux-arts de la Ville de Paris",
            wikipediaUrl = "https://fr.wikipedia.org/wiki/Petit_Palais",
            merimeeReference = "PA00088878",
            candidateLatitude = null,
            candidateLongitude = null,
            monumentLatitude = 48.8657955,
            monumentLongitude = 2.3147746,
        )

        assertEquals(100, score)
    }

    @Test
    fun referenceAloneDoesNotAcceptAChapelInAnotherPlace() {
        val score = monumentImageCandidateScore(
            candidateText = "Chapelle en Mayenne PA00088878.jpg",
            monumentName = "Musée des beaux-arts de la Ville de Paris",
            wikipediaUrl = "https://fr.wikipedia.org/wiki/Petit_Palais",
            merimeeReference = "PA00088878",
            candidateLatitude = null,
            candidateLongitude = null,
            monumentLatitude = 48.8657955,
            monumentLongitude = 2.3147746,
        )

        assertEquals(0, score)
    }

    @Test
    fun referenceAndNearbyCoordinatesCanValidateAnAliasFreeTitle() {
        val score = monumentImageCandidateScore(
            candidateText = "Vue extérieure PA00088878.jpg",
            monumentName = "Musée des beaux-arts de la Ville de Paris",
            wikipediaUrl = null,
            merimeeReference = "PA00088878",
            candidateLatitude = 48.8659,
            candidateLongitude = 2.3148,
            monumentLatitude = 48.8657955,
            monumentLongitude = 2.3147746,
        )

        assertTrue(score >= 90)
    }

    @Test
    fun popNonCommercialRestrictionIsNotAcceptedAsReusable() {
        assertTrue(!isReusablePopRights("CC BY-NC 4.0"))
    }
}
