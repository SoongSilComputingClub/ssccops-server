package com.example.ssccwebbe.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserRequestDto {
    public interface ExistGroup {} // 회원 가입시 username 존재 확인

    public interface AddGroup {} // 회원 가입시

    public interface PasswordGroup {} // 비밀번호 변경시

    public interface UpdateGroup {} // 회원 수정시

    public interface DeleteGroup {} // 회원 삭제시

    @NotBlank(groups = {ExistGroup.class, AddGroup.class, UpdateGroup.class, DeleteGroup.class})
    @Size(min = 4)
    private String username;

    @NotBlank(groups = {AddGroup.class, PasswordGroup.class})
    @Size(min = 4)
    private String nickname;

    @Email(groups = {AddGroup.class, UpdateGroup.class})
    private String email;
}
