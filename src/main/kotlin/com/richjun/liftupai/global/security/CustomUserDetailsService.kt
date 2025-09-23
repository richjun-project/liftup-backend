package com.richjun.liftupai.global.security

import com.richjun.liftupai.domain.auth.repository.UserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository
) : UserDetailsService {

    @Transactional(readOnly = true)
    override fun loadUserByUsername(identifier: String): UserDetails {
        // identifier가 이메일인지 deviceId인지 확인
        val user = if (identifier.contains("@")) {
            userRepository.findByEmail(identifier)
                .orElseThrow { UsernameNotFoundException("User not found with email: $identifier") }
        } else {
            userRepository.findByDeviceId(identifier)
                .orElseThrow { UsernameNotFoundException("User not found with deviceId: $identifier") }
        }

        return CustomUserDetails(user)
    }

    @Transactional(readOnly = true)
    fun loadUserById(id: Long): UserDetails {
        val user = userRepository.findById(id)
            .orElseThrow { UsernameNotFoundException("User not found with id: $id") }

        return CustomUserDetails(user)
    }

    @Transactional(readOnly = true)
    fun loadUserByIdentifier(identifier: String): UserDetails {
        val user = if (identifier.contains("@")) {
            userRepository.findByEmail(identifier)
                .orElseThrow { UsernameNotFoundException("User not found with email: $identifier") }
        } else {
            userRepository.findByDeviceId(identifier)
                .orElseThrow { UsernameNotFoundException("User not found with deviceId: $identifier") }
        }

        return CustomUserDetails(user)
    }

    @Transactional(readOnly = true)
    fun loadUserByEmail(email: String): UserDetails {
        val user = userRepository.findByEmail(email)
            .orElseThrow { UsernameNotFoundException("User not found with email: $email") }

        return CustomUserDetails(user)
    }

    @Transactional(readOnly = true)
    fun loadUserByDeviceId(deviceId: String): UserDetails {
        val user = userRepository.findByDeviceId(deviceId)
            .orElseThrow { UsernameNotFoundException("User not found with deviceId: $deviceId") }

        return CustomUserDetails(user)
    }
}