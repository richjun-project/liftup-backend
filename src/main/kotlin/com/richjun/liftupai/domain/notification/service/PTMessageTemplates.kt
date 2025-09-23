package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.user.entity.PTStyle
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
class PTMessageTemplates {

    fun getMorningMealMessage(style: PTStyle): String {
        val messages = when (style) {
            PTStyle.SPARTAN -> listOf(
                "일어나! 챔피언의 하루가 시작됐다! 아침 먹고 전투 준비하라! 💪",
                "기상! 전사의 아침이다! 단백질 폭탄 투하하고 오늘도 한계를 깨부숴라! 🔥",
                "눈 떴나? 침대에서 나와! 근육은 영양분을 원한다! 지금 당장 먹어라!"
            )
            PTStyle.BURNOUT -> listOf(
                "좋은 아침이에요 😊 오늘 컨디션은 어떠신가요? 건강한 아침 드세요",
                "잘 주무셨나요? 🌅 오늘도 활기찬 하루 시작하세요. 아침은 든든하게!",
                "굿모닝이에요 💖 아침 식사로 하루를 건강하게 시작해보아요"
            )
            PTStyle.GAME_MASTER -> listOf(
                "굿모닝~ 아침 뭐 먹을거야? 단백질 잊지마! 🍳",
                "일어났어? ㅋㅋ 아침밥은 필수야! 오늘도 화이팅! 😄",
                "좋은 아침! 배고프지? 맛있는거 먹고 오늘도 열심히 해보자! 🌟"
            )
            PTStyle.INFLUENCER -> listOf(
                "기상 후 대사율이 높아집니다. 단백질 20g 이상 섭취를 권장합니다",
                "아침 식사는 하루 에너지 대사의 기초입니다. 균형잡힌 영양소를 섭취하세요",
                "공복 시간이 길어지면 근손실 위험이 있습니다. 양질의 단백질과 탄수화물을 섭취하세요"
            )
            else -> listOf(
                "좋은 아침이에요! 오늘도 건강한 하루 되세요!",
                "아침 식사 하셨나요? 든든하게 드시고 화이팅!",
                "굿모닝! 맛있는 아침 드세요!"
            )
        }
        return messages.random()
    }

    fun getLunchMealMessage(style: PTStyle): String {
        val messages = when (style) {
            PTStyle.SPARTAN -> listOf(
                "점심 시간이다! 근육은 연료를 원한다! 제대로 먹어라! 🔥",
                "전투 중간 보급 시간! 단백질 충전하고 오후 전투 준비하라!",
                "점심 거르면 근손실이다! 지금 당장 영양분을 공급하라! 💪"
            )
            PTStyle.BURNOUT -> listOf(
                "점심 맛있게 드셨나요? 오후에도 화이팅이에요 💖",
                "점심 시간이네요 😊 무리하지 마시고 충분히 드세요",
                "오후를 위한 에너지 충전 시간이에요. 건강한 점심 되세요 🌸"
            )
            PTStyle.GAME_MASTER -> listOf(
                "점심 뭐 먹었어? 과식은 금물이야~ ㅋㅋ",
                "밥 먹었어? 졸리겠지만 오후도 힘내자! 😪",
                "점심 맛있게 먹고 있어? 든든하게 먹고 오후도 파이팅! 🍱"
            )
            PTStyle.INFLUENCER -> listOf(
                "점심 식사 후 2시간 뒤가 운동 최적 시간입니다. 탄수화물 적정 섭취하세요",
                "점심은 하루 칼로리의 35%를 섭취하는 것이 이상적입니다",
                "소화를 고려하여 과식은 피하되, 오후 활동을 위한 충분한 에너지를 섭취하세요"
            )
            else -> listOf(
                "점심 맛있게 드세요! 오후에도 화이팅!",
                "점심 시간이네요! 든든하게 드시고 힘내세요!",
                "오후를 위한 에너지 충전 시간이에요!"
            )
        }
        return messages.random()
    }

    fun getWorkoutReminderMessage(style: PTStyle): String {
        val messages = when (style) {
            PTStyle.SPARTAN -> listOf(
                "운동 시간이다! 변명은 필요 없다! 지금 당장 시작하라! 한계를 깨부숴라! 💪🔥",
                "30분 후 전투 시작! 준비 운동하고 전투 태세 갖춰라! 오늘도 승리하자!",
                "운동 시간 다가온다! 어제의 나를 뛰어넘어라! No Pain, No Gain! 🏋️"
            )
            PTStyle.BURNOUT -> listOf(
                "운동 시간이 다가왔네요 😊 오늘도 무리하지 말고 즐겁게 운동해요",
                "30분 후에 운동이에요. 천천히 준비하세요. 화이팅! 💕",
                "운동할 시간이 거의 다 됐어요. 부상 조심하시고 즐거운 운동 되세요 🌟"
            )
            PTStyle.GAME_MASTER -> listOf(
                "운동 갈 시간이야~ 오늘 어떤 부위? 가즈아~ 🚀",
                "야! 운동 시간이야! 준비했어? 오늘도 빡세게 해보자! 💪",
                "30분 후 운동이야! 스트레칭하고 준비해~ 화이팅! 🔥"
            )
            PTStyle.INFLUENCER -> listOf(
                "운동 30분 전입니다. 동적 스트레칭으로 체온을 상승시키고 시작하세요",
                "운동 준비 시간입니다. 관절 가동 범위 확보와 근육 활성화를 진행하세요",
                "곧 운동 시작입니다. 목표 심박수 도달을 위한 워밍업을 준비하세요"
            )
            else -> listOf(
                "운동 시간이 다가왔어요! 준비하세요!",
                "30분 후 운동이에요. 화이팅!",
                "운동 준비 시간이에요! 오늘도 힘내세요!"
            )
        }
        return messages.random()
    }

    fun getDinnerMealMessage(style: PTStyle): String {
        val messages = when (style) {
            PTStyle.SPARTAN -> listOf(
                "저녁 먹었나? 내일을 위한 영양 보충이다! 단백질 잊지 마라!",
                "저녁 시간! 근육 회복을 위한 영양소 공급하라! 내일 더 강해진다! 💪",
                "오늘의 마지막 영양 보급! 제대로 먹고 내일을 준비하라!"
            )
            PTStyle.BURNOUT -> listOf(
                "오늘 하루도 수고하셨어요 🌙 저녁은 가볍게 드시는게 좋아요",
                "저녁 시간이네요. 소화가 잘 되는 음식으로 편안하게 드세요 😊",
                "하루 마무리 식사 시간이에요. 과식하지 마시고 적당히 드세요 💖"
            )
            PTStyle.GAME_MASTER -> listOf(
                "저녁 뭐 먹을래? 야식은 적이야! ㅎㅎ",
                "저녁 먹었어? 너무 많이 먹으면 내일 후회해~ 😅",
                "저녁 시간! 맛있는거 먹되 적당히~ 야식 참아! 🍽️"
            )
            PTStyle.INFLUENCER -> listOf(
                "저녁 식사는 취침 3시간 전 완료를 권장합니다. 소화가 쉬운 음식 위주로 구성하세요",
                "저녁은 하루 칼로리의 25-30%가 적정합니다. 단백질 위주의 식단을 권장합니다",
                "야간 근육 회복을 위해 카제인 단백질 섭취를 고려하세요"
            )
            else -> listOf(
                "저녁 맛있게 드세요! 오늘 하루도 수고하셨어요!",
                "저녁 시간이네요. 적당히 드시고 푹 쉬세요!",
                "하루 마무리 식사 시간이에요!"
            )
        }
        return messages.random()
    }

    fun getSleepPrepMessage(style: PTStyle): String {
        val messages = when (style) {
            PTStyle.SPARTAN -> listOf(
                "오늘 훈련 잘했다! 내일은 더 강해져서 돌아와라! 💪",
                "전투 종료! 충분히 쉬고 내일 더 강한 전사가 되어라! 🔥",
                "오늘도 한계를 뛰어넘었다! 푹 쉬고 내일 다시 전투다!"
            )
            PTStyle.BURNOUT -> listOf(
                "오늘도 고생하셨어요 😴 푹 쉬고 내일 또 만나요",
                "수고 많으셨어요. 편안한 밤 되세요 🌙 꿀잠 자요",
                "오늘 하루도 잘 마무리하셨네요. 푹 쉬세요 💤"
            )
            PTStyle.GAME_MASTER -> listOf(
                "수고했어! 꿀잠 자고 내일 봐~ 😪",
                "오늘도 고생했어! 푹 자고 내일 또 열심히 하자! 💤",
                "잘자~ 내일도 화이팅하자! Good night! 🌙"
            )
            PTStyle.INFLUENCER -> listOf(
                "수면 중 근육 회복이 일어납니다. 7-8시간 충분한 수면을 취하세요",
                "성장호르몬 분비를 위해 22-02시 사이 숙면이 중요합니다",
                "질 좋은 수면은 근육 성장과 회복의 핵심입니다. 충분한 휴식 취하세요"
            )
            else -> listOf(
                "오늘도 수고하셨어요! 푹 쉬세요!",
                "잘자요! 내일 또 만나요!",
                "편안한 밤 되세요! 꿀잠 자요!"
            )
        }
        return messages.random()
    }

    /**
     * 운동 완료 후 축하 메시지
     */
    fun getWorkoutCompleteMessage(style: PTStyle): String {
        val messages = when (style) {
            PTStyle.SPARTAN -> listOf(
                "훌륭하다! 오늘도 한계를 넘었다! 내일은 더 강해진다! 💪🔥",
                "전투 완료! 승리다! 근육이 성장하고 있다! 계속 전진하라!",
                "잘했다 전사여! 오늘의 고통이 내일의 힘이 된다!"
            )
            PTStyle.BURNOUT -> listOf(
                "정말 수고하셨어요! 👏 오늘도 잘 해내셨네요",
                "운동 완료! 대단해요! 충분히 쉬세요 😊",
                "오늘도 열심히 하셨네요! 정말 멋져요 💖"
            )
            PTStyle.GAME_MASTER -> listOf(
                "오 끝났어? 수고했어! 오늘도 빡셌지? ㅋㅋ 💪",
                "운동 끝! 고생했어~ 단백질 보충 잊지마! 😄",
                "잘했어! 오늘도 열심히 했네~ 내일도 화이팅! 🔥"
            )
            PTStyle.INFLUENCER -> listOf(
                "운동 완료. 30분 이내 단백질 섭취로 회복을 촉진하세요",
                "목표 운동량 달성. 48시간 충분한 휴식 후 다음 세션 진행하세요",
                "효과적인 운동이었습니다. 스트레칭과 영양 보충을 잊지 마세요"
            )
            else -> listOf(
                "운동 완료! 정말 수고하셨어요!",
                "오늘도 잘 해내셨네요! 대단해요!",
                "운동 끝! 충분히 쉬세요!"
            )
        }
        return messages.random()
    }

    /**
     * 운동 쉬는 날 메시지
     */
    fun getRestDayMessage(style: PTStyle): String {
        val messages = when (style) {
            PTStyle.SPARTAN -> listOf(
                "오늘은 휴식일이다! 근육이 성장하는 시간! 충분히 먹고 쉬어라!",
                "전투 중지! 회복의 시간이다! 내일을 위해 재충전하라!",
                "휴식도 훈련의 일부다! 제대로 쉬고 더 강해져서 돌아와라!"
            )
            PTStyle.BURNOUT -> listOf(
                "오늘은 휴식하는 날이에요 😌 충분히 쉬세요",
                "쉬는 날도 중요해요. 몸 회복에 집중하세요 💆",
                "오늘은 편안하게 보내세요. 휴식도 운동의 일부예요 🌸"
            )
            PTStyle.GAME_MASTER -> listOf(
                "오늘 쉬는 날이지? 푹 쉬어~ 내일 또 빡세게! 😎",
                "휴식 day! 맛있는거 먹고 쉬자~ ㅋㅋ",
                "오늘은 쉬는 날! 넷플릭스 타임? 😄"
            )
            PTStyle.INFLUENCER -> listOf(
                "휴식일입니다. 근육 회복과 글리코겐 보충에 집중하세요",
                "능동적 휴식을 권장합니다. 가벼운 산책이나 스트레칭을 고려하세요",
                "휴식은 근육 성장의 필수 요소입니다. 충분한 영양 섭취와 수면을 취하세요"
            )
            else -> listOf(
                "오늘은 휴식하는 날이에요! 충분히 쉬세요!",
                "쉬는 날도 중요해요. 몸 회복에 집중하세요!",
                "오늘은 편안하게 보내세요!"
            )
        }
        return messages.random()
    }
}