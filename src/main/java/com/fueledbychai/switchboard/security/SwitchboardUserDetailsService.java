package com.fueledbychai.switchboard.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class SwitchboardUserDetailsService implements UserDetailsService {

    private final SwitchboardUserRepository switchboardUserRepository;

    public SwitchboardUserDetailsService(SwitchboardUserRepository switchboardUserRepository) {
        this.switchboardUserRepository = switchboardUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SwitchboardUserEntity user = switchboardUserRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRoleName())
                .build();
    }
}
