package com.yingji.app.domain.ai

import org.junit.Assert.*
import org.junit.Test

class RecommendationProfileTest {
 @Test fun sameNamedExplicitEntitiesKeepExclusionAndOriginalRoomIdsStayUntouched() {
  val original=ProfileSnapshot(explicitPreferences=listOf(
   ExplicitPreference(ProfileFeature.category(7,"科幻"),PreferenceDirection.LIKE),
   ExplicitPreference(ProfileFeature.tag(7,"科幻"),PreferenceDirection.DISLIKE)))
  val projected=recommendationProfile(original)
  assertEquals(listOf(ExplicitPreference(ProfileFeature.topic("科幻"),PreferenceDirection.DISLIKE)),projected.explicitPreferences)
  assertEquals(listOf("category:7","tag:7"),original.explicitPreferences.map{it.feature.id})
 }
 @Test fun correctionRemovesOnlyItsEntityBeforeLabelsAreCombined() {
  val original=ProfileSnapshot(settings=ProfileSourceSettings(true,true,true),inferredPreferences=listOf(
   InferredPreference(ProfileFeature.category(7,"科幻"),ProfileSource.RATING,1,positiveEvidence=1),
   InferredPreference(ProfileFeature.tag(7,"科幻"),ProfileSource.RATING,1,negativeEvidence=1)),suppressedFeatureIds=setOf("tag:7"))
  val projected=recommendationProfile(original)
  assertEquals(1,CandidatePreferences.fromProfile(projected).inferred["科幻"])
  assertEquals(2,original.inferredPreferences.size);assertEquals(setOf("tag:7"),original.suppressedFeatureIds)
 }
 @Test fun disabledSourcesAndViewingBehaviorNeverCreateTransferredLikes() {
  val p=ProfileSnapshot(settings=ProfileSourceSettings(false,true,false),inferredPreferences=listOf(
   InferredPreference(ProfileFeature.tag(7,"科幻"),ProfileSource.RATING,1,positiveEvidence=1),
   InferredPreference(ProfileFeature.tag(8,"喜剧"),ProfileSource.FEEDBACK,1,positiveEvidence=1),
   InferredPreference(ProfileFeature.tag(9,"动画"),ProfileSource.BEHAVIOR,1,completedWatches=3,repeatWatches=2)))
  val projected=recommendationProfile(p)
  assertTrue(CandidatePreferences.fromProfile(projected).inferred.isEmpty())
  assertTrue(WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU),AiIntegrationContext(projected,true)).profileSummary.inferred.isEmpty())
 }
 @Test fun currentCategoryAndTagLabelsReplaceOldStoredPreferenceLabelsWithoutChangingIdentity() {
  val p=ProfileSnapshot(explicitPreferences=listOf(ExplicitPreference(ProfileFeature.tag(7,"旧名"),PreferenceDirection.LIKE)))
  val projected=recommendationProfile(p,mapOf("tag:7" to "科幻"))
  assertEquals("topic:科幻",projected.explicitPreferences.single().feature.id)
  assertEquals("tag:7",p.explicitPreferences.single().feature.id)
  assertEquals("旧名",p.explicitPreferences.single().feature.label)
 }
 @Test fun explicitChoiceStillWinsAndLocalVersionsArePreserved() {
  val p=ProfileSnapshot(settings=ProfileSourceSettings(true,false,true),token=ProfileToken(8,3),
   explicitPreferences=listOf(ExplicitPreference(ProfileFeature.tag(7,"喜剧"),PreferenceDirection.DISLIKE)),
   inferredPreferences=listOf(InferredPreference(ProfileFeature.category(8,"喜剧"),ProfileSource.RATING,10,positiveEvidence=10)))
  val q=recommendationProfile(p);assertEquals(p.token,q.token)
  assertEquals(PreferenceDirection.DISLIKE,CandidatePreferences.fromProfile(q).explicit["喜剧"])
 }
}
