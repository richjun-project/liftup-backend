# AI Chat API Documentation

## Overview
AI 채팅 기능을 통해 사용자가 Gemini AI와 실시간으로 대화할 수 있습니다.

## Endpoint

### AI 채팅
```
POST /api/ai/chat
```

### Request

#### Headers
```
Authorization: Bearer {JWT_TOKEN}
Content-Type: application/json
```

#### Body
```json
{
  "message": "오늘 등 운동 추천해줘",
  "context": {
    "workout_type": "PULL",
    "current_exercise": "랫풀다운",
    "user_goal": "근육량 증가"
  }
}
```

#### Request Fields
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| message | String | Yes | 사용자 메시지 (1~1000자) |
| context | Object | No | 대화 컨텍스트 |
| context.workout_type | String | No | 현재 운동 타입 |
| context.current_exercise | String | No | 현재 진행 중인 운동 |
| context.user_goal | String | No | 사용자 목표 |

### Response

#### Success Response (200 OK)
```json
{
  "success": true,
  "data": {
    "reply": "안녕하세요! 등 운동 추천드릴게요! 💪\n\n오늘은 등 근육을 전체적으로 자극할 수 있는 운동들을 추천드립니다:\n\n1. 풀업 (또는 어시스트 풀업) - 3세트 x 8-10회\n2. 바벨 로우 - 4세트 x 10-12회\n3. 랫풀다운 - 3세트 x 12-15회\n4. 케이블 로우 - 3세트 x 12-15회\n5. 페이스풀 - 3세트 x 15-20회\n\n각 세트 간 휴식은 60-90초를 추천드립니다. 화이팅! 😊",
    "timestamp": "2025-01-16T10:30:00",
    "message_id": "550e8400-e29b-41d4-a716-446655440000",
    "suggestions": [
      "등 운동 보기",
      "운동 프로그램 생성"
    ]
  }
}
```

#### Response Fields
| Field | Type | Description |
|-------|------|-------------|
| reply | String | AI의 응답 메시지 |
| timestamp | String | 응답 시간 (ISO 8601) |
| message_id | String | 메시지 고유 ID |
| suggestions | Array<String> | 추천 액션 (최대 3개) |

### Error Responses

#### 400 Bad Request
```json
{
  "success": false,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "메시지는 필수입니다"
  }
}
```

#### 401 Unauthorized
```json
{
  "success": false,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "인증이 필요합니다"
  }
}
```

#### 500 Internal Server Error
```json
{
  "success": false,
  "error": {
    "code": "AI_ERROR",
    "message": "AI 서비스 오류가 발생했습니다"
  }
}
```

## Usage Examples

### 1. 기본 채팅
```bash
curl -X POST https://api.liftupai.com/api/ai/chat \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "벤치프레스 자세 알려줘"
  }'
```

### 2. 컨텍스트를 포함한 채팅
```bash
curl -X POST https://api.liftupai.com/api/ai/chat \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "다음 세트는 무게를 올려야 할까?",
    "context": {
      "workout_type": "PUSH",
      "current_exercise": "벤치프레스",
      "user_goal": "근력 향상"
    }
  }'
```

### 3. Flutter 구현 예제

```dart
// chat_service.dart
class ChatService {
  final Dio _dio;

  ChatService(this._dio);

  Future<ChatResponse> sendMessage(String message, {ChatContext? context}) async {
    try {
      final response = await _dio.post(
        '/api/ai/chat',
        data: {
          'message': message,
          if (context != null) 'context': context.toJson(),
        },
      );

      return ChatResponse.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }
}

// chat_screen.dart
class ChatScreen extends StatefulWidget {
  @override
  _ChatScreenState createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final TextEditingController _controller = TextEditingController();
  final List<ChatMessage> _messages = [];
  final ChatService _chatService = ChatService();
  bool _isLoading = false;

  Future<void> _sendMessage() async {
    if (_controller.text.isEmpty) return;

    final userMessage = _controller.text;
    setState(() {
      _messages.add(ChatMessage(
        text: userMessage,
        isUser: true,
        timestamp: DateTime.now(),
      ));
      _isLoading = true;
    });

    _controller.clear();

    try {
      final response = await _chatService.sendMessage(userMessage);

      setState(() {
        _messages.add(ChatMessage(
          text: response.reply,
          isUser: false,
          timestamp: DateTime.now(),
          suggestions: response.suggestions,
        ));
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _messages.add(ChatMessage(
          text: '오류가 발생했습니다. 다시 시도해주세요.',
          isUser: false,
          timestamp: DateTime.now(),
        ));
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('AI 트레이너'),
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              reverse: true,
              itemCount: _messages.length,
              itemBuilder: (context, index) {
                final message = _messages[_messages.length - 1 - index];
                return ChatBubble(message: message);
              },
            ),
          ),
          if (_isLoading)
            Padding(
              padding: EdgeInsets.all(8.0),
              child: CircularProgressIndicator(),
            ),
          Container(
            padding: EdgeInsets.all(8.0),
            decoration: BoxDecoration(
              color: Colors.white,
              boxShadow: [
                BoxShadow(
                  offset: Offset(0, -2),
                  blurRadius: 4,
                  color: Colors.black12,
                ),
              ],
            ),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _controller,
                    decoration: InputDecoration(
                      hintText: '메시지를 입력하세요...',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(24),
                      ),
                    ),
                    onSubmitted: (_) => _sendMessage(),
                  ),
                ),
                SizedBox(width: 8),
                IconButton(
                  icon: Icon(Icons.send),
                  onPressed: _sendMessage,
                  color: Theme.of(context).primaryColor,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
```

## Features

### 1. 자동 컨텍스트 인식
- 현재 운동 상황을 인식하여 더 정확한 답변 제공
- 사용자 목표에 맞춤형 조언

### 2. 추천 액션 제안
- AI 응답에 기반한 다음 액션 추천
- 빠른 액션 버튼으로 편의성 향상

### 3. 개인화된 응답
- 사용자 프로필 정보 기반 맞춤형 답변
- 운동 수준, 목표, 선호도 고려

### 4. 안전한 메시지 처리
- 메시지 길이 제한 (1000자)
- 부적절한 내용 필터링
- Rate limiting 적용

## Best Practices

1. **컨텍스트 활용**
   - 가능한 많은 컨텍스트 정보 제공
   - 현재 운동 상황 전달

2. **명확한 질문**
   - 구체적이고 명확한 질문
   - 한 번에 하나의 주제

3. **응답 활용**
   - suggestions 활용하여 UX 개선
   - 대화 히스토리 관리

4. **에러 처리**
   - 네트워크 오류 대비
   - Fallback 메시지 준비

## Notes

- Gemini AI 모델 사용 (gemini-pro)
- 응답 시간: 평균 1-3초
- 일일 요청 제한: 사용자당 1000건
- 메시지 히스토리는 클라이언트에서 관리