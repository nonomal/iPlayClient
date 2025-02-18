//
//  PlayerControlView.h
//  iPlayClient
//
//  Created by 赫拉 on 2024/3/30.
//

#import <UIKit/UIKit.h>
#import "VideoPlayer.h"

NS_ASSUME_NONNULL_BEGIN

@interface PlayerControlView : UIView
@property (nonatomic, copy) NSString *title;
@property (nonatomic, assign) NSUInteger iconSize;
@property (nonatomic, weak) id delegate;
@property (nonatomic, strong) id<VideoPlayer> player;
@property (nonatomic, weak) UIView *parentView;
@property (nonatomic, strong) UIView *playButton;
@property (nonatomic, strong) UIView *fullscreenButton;
@property (nonatomic, strong) UIView *captionButton;
@property (nonatomic, strong) UIView *settingButton;
@property (nonatomic, strong) UIProgressView *progressBar;
@property (nonatomic, strong) NSProgress *progress;
@property (nonatomic, strong) UISlider *sliderBar;
@property (nonatomic, strong) UILabel *durationLabel;
@property (nonatomic, strong) UILabel *titleLabel;
@property (nonatomic, strong) UIActivityIndicatorView *indicator;
@property (nonatomic, strong) NSDateComponentsFormatter *timeFormatter;
@property (nonatomic, assign) BOOL isControlsVisible;
@property (nonatomic, assign) BOOL isFullscreen;

- (void)hideControls;
- (void)showControls;
@end

NS_ASSUME_NONNULL_END
