package core.domain.chat.dto;


import core.domain.user.entity.User;

import java.time.Instant;

public record UserResponseDto(
        Long userId,
        String name,
        String firstName,
        String lastName,
        String sex,
        String birthdate,
        String country,
        String introduction,
        String purpose,
        String language,
        String translateLanguage,
        String hobby,
        Instant createdAt,
        Instant updatedAt,
        String provider,
        String socialId,
        String email,
        boolean isNewUser,
        boolean agreedToPushNotification,
        boolean agreedToTerms,
        String ImageUrl
) {
    public static UserResponseDto from(User user,String ImageUrl) {
        return new UserResponseDto(
                user.getId(),
                user.getName(),
                user.getFirstName(),
                user.getLastName(),
                user.getSex(),
                user.getBirthdate(),
                user.getCountry(),
                user.getIntroduction(),
                user.getPurpose(),
                user.getLanguage(),
                user.getTranslateLanguage(),
                user.getHobby(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getProvider(),
                user.getSocialId(),
                user.getEmail(),
                user.isNewUser(),
                user.isAgreedToPushNotification(),
                user.isAgreedToTerms(),
                ImageUrl
        );
    }
    /**
     * API 조회 실패 또는 탈퇴한 유저 등 정보가 없을 경우를 대비하여
     * 반환할 기본 "알 수 없는 유저" 객체를 생성합니다.
     */
    /**
     * API 조회 실패 또는 탈퇴한 유저 등 정보가 없을 경우를 대비하여
     * 반환할 기본 "알 수 없는 유저" 객체를 생성합니다.
     */
    public static UserResponseDto unknown() {
        // 서비스 정책에 맞게 모든 필드의 기본값을 정의합니다.
        return new UserResponseDto(
                0L,                 // userId
                "(알 수 없음)",       // name
                "",                 // firstName
                "",                 // lastName
                null,               // sex
                null,               // birthdate
                null,               // country
                null,               // introduction
                null,               // purpose
                null,               // language
                null,               // translateLanguage
                null,               // hobby
                null,               // createdAt
                null,               // updatedAt
                null,               // provider
                null,               // socialId
                null,               // email
                false,              // isNewUser
                false,              // agreedToPushNotification
                false,              // agreedToTerms
                null                // ImageUrl
        );
    }

}