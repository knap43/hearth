# fish completions for retrohub
complete -c retrohub -f
complete -c retrohub -n __fish_use_subcommand -a list -d 'List the games in the library'
complete -c retrohub -n __fish_use_subcommand -a run -d 'Launch a game'
complete -c retrohub -n __fish_use_subcommand -a doctor -d 'Show paths and runners'
complete -c retrohub -n '__fish_seen_subcommand_from list' -l names -d 'Names only'
complete -c retrohub -n '__fish_seen_subcommand_from run' -s w -l wait -d 'Stay attached and count playtime'
complete -c retrohub -n '__fish_seen_subcommand_from run' -a '(retrohub list --names 2>/dev/null)'
