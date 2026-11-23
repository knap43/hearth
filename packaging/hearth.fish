# fish completions for hearth
complete -c hearth -f
complete -c hearth -n __fish_use_subcommand -a list -d 'List the games in the library'
complete -c hearth -n __fish_use_subcommand -a run -d 'Launch a game'
complete -c hearth -n __fish_use_subcommand -a doctor -d 'Show paths and runners'
complete -c hearth -n '__fish_seen_subcommand_from list' -l names -d 'Names only'
complete -c hearth -n '__fish_seen_subcommand_from run' -s w -l wait -d 'Stay attached and count playtime'
complete -c hearth -n '__fish_seen_subcommand_from run' -a '(hearth list --names 2>/dev/null)'
