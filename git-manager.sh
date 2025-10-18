#!/bin/bash

# CloudStream Git Yönetim Script'i
# Erdodo tarafından oluşturuldu

clear
echo "🚀 CloudStream Git Yönetim Menüsü"
echo "=================================="
echo ""
echo "1. 📥 CloudStream'den güncellemeleri çek (upstream)"
echo "2. 📥 Erdodo repo'sundan güncellemeleri çek (origin)"
echo "3. 📤 Değişiklikleri Erdodo repo'suna push et"
echo "4. 🏗️  APK build et"
echo "5. 📊 Git durumunu göster"
echo "6. ❌ Çıkış"
echo ""
read -p "Seçiminizi yapın (1-6): " choice

case $choice in
    1)
        echo ""
        echo "🔄 CloudStream'den güncellemeler çekiliyor..."
        echo "=============================================="
        git pull upstream master --rebase
        if [ $? -eq 0 ]; then
            echo "✅ CloudStream güncellemeleri başarıyla alındı!"
        else
            echo "❌ Hata oluştu! Çakışma olabilir."
        fi
        ;;
    
    2)
        echo ""
        echo "🔄 Erdodo repo'sundan güncellemeler çekiliyor..."
        echo "==============================================="
        git pull origin master
        if [ $? -eq 0 ]; then
            echo "✅ Erdodo repo güncellemeleri başarıyla alındı!"
        else
            echo "❌ Hata oluştu!"
        fi
        ;;
    
    3)
        echo ""
        echo "📋 Değişiklikler kontrol ediliyor..."
        git status
        echo ""
        read -p "Commit mesajı girin: " commit_msg
        
        if [ -z "$commit_msg" ]; then
            commit_msg="Update: $(date '+%Y-%m-%d %H:%M:%S')"
        fi
        
        echo ""
        echo "📤 Değişiklikler push ediliyor..."
        echo "================================"
        git add -A
        git commit -m "$commit_msg"
        git push origin master
        
        if [ $? -eq 0 ]; then
            echo "✅ Değişiklikler başarıyla push edildi!"
        else
            echo "❌ Push işleminde hata oluştu!"
        fi
        ;;
    
    4)
        echo ""
        echo "🏗️  APK build ediliyor..."
        echo "=========================="
        ./gradlew app:assembleStableDebug
        
        if [ $? -eq 0 ]; then
            echo ""
            echo "✅ APK başarıyla build edildi!"
            echo "📱 APK konumu: app/build/outputs/apk/stable/debug/app-stable-debug.apk"
        else
            echo "❌ Build işleminde hata oluştu!"
        fi
        ;;
    
    5)
        echo ""
        echo "📊 Git Durum Raporu"
        echo "==================="
        echo ""
        echo "🌿 Mevcut branch:"
        git branch --show-current
        echo ""
        echo "📈 Git durumu:"
        git status
        echo ""
        echo "📝 Son 3 commit:"
        git log --oneline -3
        echo ""
        echo "🔗 Remote'lar:"
        git remote -v
        ;;
    
    6)
        echo ""
        echo "👋 Görüşürüz!"
        exit 0
        ;;
    
    *)
        echo ""
        echo "❌ Geçersiz seçim! Lütfen 1-6 arası bir sayı girin."
        ;;
esac

echo ""
echo "⏎ Devam etmek için Enter'a basın..."
read
exec "$0"  # Script'i yeniden başlat